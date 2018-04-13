package osmesa

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import com.amazonaws.services.s3.model.CannedAccessControlList._
import geotrellis.spark._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.io.index.zcurve.Z2
import geotrellis.spark.io.s3._
import geotrellis.spark.tiling._
import geotrellis.vector._
import geotrellis.vectortile._
import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object GenerateVT {

  lazy val logger = Logger.getRootLogger()

  type VTF[G <: Geometry] = Feature[G, Map[String, Value]]

  def save(vectorTiles: RDD[(SpatialKey, VectorTile)], zoom: Int, bucket: String, prefix: String) = {
    vectorTiles
      .mapValues(_.toBytes)
      .saveToS3({ sk: SpatialKey => s"s3://${bucket}/${prefix}/${zoom}/${sk.col}/${sk.row}.mvt" },
                putObjectModifier = { o => o.withCannedAcl(PublicRead) })
  }

  def saveHadoop(vectorTiles: RDD[(SpatialKey, VectorTile)], zoom: Int, uri: String) = {
    vectorTiles
      .mapValues(_.toBytes)
      .saveToHadoop({ sk: SpatialKey => s"${uri}/${zoom}/${sk.col}/${sk.row}.mvt" })
  }

  def saveInZips(vectorTiles: RDD[(SpatialKey, VectorTile)], zoom: Int, bucket: String, prefix: String) = {
    val offset = zoom % 8

    val s3PathFromKey: SpatialKey => String =
    { sk =>
      s"s3://${bucket}/${prefix}/${zoom - offset}/${sk.col}/${sk.row}.zip"
    }

    vectorTiles
      .mapValues(_.toBytes)
      .map { case (sk, data) => (SpatialKey(sk._1 / Math.pow(2, offset).intValue, sk._2 / Math.pow(2, offset).intValue), (sk, data)) }
      .groupByKey
      .mapValues { data =>
        val out = new ByteArrayOutputStream
        val zip = new ZipOutputStream(out)

        data
          .toSeq
          .sortBy { case (sk, _) => Z2(sk.col, sk.row).z }
          .foreach { case (sk, entry)  =>
            zip.putNextEntry(new ZipEntry(s"${zoom}/${sk.col}/${sk.row}.mvt"))
            zip.write(entry)
            zip.closeEntry()
          }

        zip.close()

        out.toByteArray
      }
      .saveToS3(s3PathFromKey, putObjectModifier = { o => o.withCannedAcl(PublicRead) })
  }

  def keyToLayout[G <: Geometry](features: RDD[VTF[G]], layout: LayoutDefinition): RDD[(SpatialKey, (SpatialKey, VTF[G]))] = {
    features.flatMap{ feat =>
      val g = feat.geom
      val keys = layout.mapTransform.keysForGeometry(g)
      keys.map{ k => (k, (k, feat)) }
    }
  }

  def upLevel[G <: Geometry](keyedGeoms: RDD[(SpatialKey, (SpatialKey, VTF[G]))]): RDD[(SpatialKey, (SpatialKey, VTF[G]))] = {
    keyedGeoms.map{ case (key, (_, feat)) => {
      val SpatialKey(x, y) = key
      val newKey = SpatialKey(x/2, y/2)
      (newKey, (newKey, feat))
    }}
  }

  def makeVectorTiles[G <: Geometry](keyedGeoms: RDD[(SpatialKey, (SpatialKey, VTF[G]))], layout: LayoutDefinition, layerName: String): RDD[(SpatialKey, VectorTile)] = {
    type FeatureTup = (Seq[VTF[Point]], Seq[VTF[MultiPoint]], Seq[VTF[Line]], Seq[VTF[MultiLine]], Seq[VTF[Polygon]], Seq[VTF[MultiPolygon]])

    def timedIntersect[G <: Geometry](geom: G, ex: Extent, id: Any) = {
      val future = Future { geom.intersection(ex) }
      Try(Await.result(future, 500 milliseconds)) match {
        case Success(res) => res
        case Failure(_) =>
          logger.warn(s"Could not intersect $geom with $ex [feature id=$id]")
          NoResult
      }
    }

    def create(arg: (SpatialKey, VTF[Geometry])): FeatureTup = {
      val (sk, feat) = arg
      val fid = feat.data("__id").asInstanceOf[VString].value
      val baseEx = layout.mapTransform(sk)
      // TODO this is where buffer gets configured; allow it to be provided per-layer as a %
      // val ex = Extent(baseEx.xmin - baseEx.width, baseEx.ymin - baseEx.height, baseEx.xmax + baseEx.width, baseEx.ymax + baseEx.height)
      val ex = baseEx
      feat.geom match {
        case pt: Point => (Seq(PointFeature(pt, feat.data)), Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
        case l: Line =>
          timedIntersect(l, ex, fid) match {
            // case NoResult => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            case LineResult(res) => (Seq.empty, Seq.empty, Seq(LineFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty)
            case MultiLineResult(res) => (Seq.empty, Seq.empty, Seq.empty, Seq(MultiLineFeature(res, feat.data)), Seq.empty, Seq.empty)
            // case PointResult(res) => (Seq(PointFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            // case MultiPointResult(res) => (Seq.empty, Seq(MultiPointFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            case GeometryCollectionResult(res) =>
              val gc = res.geometryCollections(0)
              gc.lines.size match {
                case 0 => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty) // should never happen
                case 1 => (Seq.empty, Seq.empty, Seq(LineFeature(gc.lines(0), feat.data)), Seq.empty, Seq.empty, Seq.empty)
                case _ => (Seq.empty, Seq.empty, Seq.empty, Seq(MultiLineFeature(MultiLine(gc.lines), feat.data)), Seq.empty, Seq.empty)
              }
            case _ => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
          }
        case ml: MultiLine =>
          timedIntersect(ml, ex, fid) match {
            // non results and point results should not happen due to buffering
            // case NoResult => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            // case PointResult(res) => (Seq(PointFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            // case MultiPointResult(res) => (Seq.empty, Seq(MultiPointFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            case LineResult(res) => (Seq.empty, Seq.empty, Seq(LineFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty)
            case MultiLineResult(res) => (Seq.empty, Seq.empty, Seq.empty, Seq(MultiLineFeature(res, feat.data)), Seq.empty, Seq.empty)
            case GeometryCollectionResult(res) =>
              val gc = res.geometryCollections(0)
              gc.lines.size match {
                case 0 => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty) // should never happen
                case 1 => (Seq.empty, Seq.empty, Seq(LineFeature(gc.lines(0), feat.data)), Seq.empty, Seq.empty, Seq.empty)
                case _ => (Seq.empty, Seq.empty, Seq.empty, Seq(MultiLineFeature(MultiLine(gc.lines), feat.data)), Seq.empty, Seq.empty)
              }
            case _ => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
          }
        case p: Polygon =>
          timedIntersect(p, ex, fid) match {
            // should only see (or care about) polygon intersection results
            // case NoResult => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            // case LineResult(res) => (Seq.empty, Seq.empty, Seq(LineFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty)
            // case MultiLineResult(res) => (Seq.empty, Seq.empty, Seq.empty, Seq(MultiLineFeature(res, feat.data)), Seq.empty, Seq.empty)
            // case PointResult(res) => (Seq(PointFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            // case MultiPointResult(res) => (Seq.empty, Seq(MultiPointFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            case PolygonResult(res) => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq(PolygonFeature(res, feat.data)), Seq.empty)
            case MultiPolygonResult(res) => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq(MultiPolygonFeature(res, feat.data)))
            case GeometryCollectionResult(res) =>
              val gc = res.geometryCollections(0)
              gc.polygons.size match {
                case 0 => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty) // should never happen
                case 1 => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq(PolygonFeature(gc.polygons(0), feat.data)), Seq.empty)
                case _ => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq(MultiPolygonFeature(MultiPolygon(gc.polygons), feat.data)))
              }
            case _ => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
          }
        case mp: MultiPolygon => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq(MultiPolygonFeature(mp, feat.data)))
          timedIntersect(mp, ex, fid) match {
            // should only see (or care about) polygon intersection results
            // case NoResult => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            // case LineResult(res) => (Seq.empty, Seq.empty, Seq(LineFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty)
            // case MultiLineResult(res) => (Seq.empty, Seq.empty, Seq.empty, Seq(MultiLineFeature(res, feat.data)), Seq.empty, Seq.empty)
            // case PointResult(res) => (Seq(PointFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            // case MultiPointResult(res) => (Seq.empty, Seq(MultiPointFeature(res, feat.data)), Seq.empty, Seq.empty, Seq.empty, Seq.empty)
            case PolygonResult(res) => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq(PolygonFeature(res, feat.data)), Seq.empty)
            case MultiPolygonResult(res) => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq(MultiPolygonFeature(res, feat.data)))
            case GeometryCollectionResult(res) =>
              val gc = res.geometryCollections(0)
              gc.polygons.size match {
                case 0 => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty) // should never happen
                case 1 => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq(PolygonFeature(gc.polygons(0), feat.data)), Seq.empty)
                case _ => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq(MultiPolygonFeature(MultiPolygon(gc.polygons), feat.data)))
              }
            case _ => (Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty)
          }
      }
    }

    def merge(tup: FeatureTup, feat: (SpatialKey, VTF[Geometry])): FeatureTup =
      combine(tup, create(feat))

    def combine(tup1: FeatureTup, tup2: FeatureTup): FeatureTup = {
      val (pt1, mpt1, l1, ml1, p1, mp1) = tup1
      val (pt2, mpt2, l2, ml2, p2, mp2) = tup2
      (pt1 ++ pt2, mpt1 ++ mpt2, l1 ++ l2, ml1 ++ ml2, p1 ++ p2, mp1 ++ mp2)
    }

    keyedGeoms
      .combineByKey(create, merge, combine)
      .map { case (sk, tup) => {
        val (pts, mpts, ls, mls, ps, mps) = tup
        val extent = layout.mapTransform(sk)

        val layer = StrictLayer(
          name=layerName,
          tileWidth=4096,
          version=2,
          tileExtent=extent,
          points=pts,
          multiPoints=mpts,
          lines=ls,
          multiLines=mls,
          polygons=ps.sortWith(_.area > _.area),
          multiPolygons=mps.sortWith(_.area > _.area)
        )

        (sk, VectorTile(Map(layerName -> layer), extent))
      }}
  }

}