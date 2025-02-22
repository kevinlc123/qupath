package qupath.lib.roi.jts;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.locationtech.jts.awt.PointTransformation;
import org.locationtech.jts.awt.ShapeReader;
import org.locationtech.jts.awt.ShapeWriter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateList;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.operation.overlay.snap.GeometrySnapper;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;
import org.locationtech.jts.simplify.VWSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.PathArea;
import qupath.lib.roi.interfaces.PathLine;
import qupath.lib.roi.interfaces.PathPoints;
import qupath.lib.roi.interfaces.ROI;

/**
 * Convert between QuPath {@code ROI} objects and Java Topology Suite {@code Geometry} objects.
 *
 * @author Pete Bankhead
 */
public class ConverterJTS {
	
	private static Logger logger = LoggerFactory.getLogger(ConverterJTS.class);

    private GeometryFactory factory;

    private double pixelHeight, pixelWidth;
    private double flatness = 0.5;

    private AffineTransform transform = null;

    private ShapeReader shapeReader;
    private ShapeWriter shapeWriter;
    
    private static ConverterJTS DEFAULT_INSTANCE = new Builder().build();
    
    /**
     * Convert a JTS Geometry to a QuPath ROI.
     * @param geometry
     * @return
     */
    public static ROI convertGeometryToROI(Geometry geometry, ImagePlane plane) {
    	return DEFAULT_INSTANCE.geometryToROI(geometry, plane);
    }
    
    /**
     * Convert to QuPath ROI to a JTS Geometry.
     * @param roi
     * @return
     */
    public static Geometry convertROIToGeometry(ROI roi) {
    	return DEFAULT_INSTANCE.roiToGeometry(roi);
    }

    /**
     * Convert a JTS Geometry to a java.awt.Shape.
     * @param geometry
     * @return
     */
    public static Shape convertROIToShape(Geometry geometry) {
    	return DEFAULT_INSTANCE.geometryToShape(geometry);
    }

    
    /**
     * Builder to help define how ROIs and Geometry objects should be converted.
     */
    public static class Builder {
    	
    	private GeometryFactory factory;

        private double pixelHeight = 1;
        private double pixelWidth = 1;
        private double flatness = 0.5;
    	
        /**
         * Default constructor for a builder with flatness 0.5 and pixel width/height of 1.0.
         */
    	public Builder() {}
    	
    	/**
    	 * Specify the pixel width and height, used to scale x and y coordinates during conversion (default is 1.0 for both).
    	 * @param pixelWidth
    	 * @param pixelHeight
    	 * @return
    	 */
    	public Builder pixelSize(double pixelWidth, double pixelHeight) {
    		this.pixelWidth = pixelWidth;
    		this.pixelHeight = pixelHeight;
    		return this;
    	}
    	
    	/**
    	 * Specify the flatness for any operation where a PathIterator is required.
    	 * @param flatness
    	 * @return
    	 */
    	public Builder flatness(double flatness) {
    		this.flatness = flatness;
    		return this;
    	}
    	
    	/**
    	 * Specify the GeometryFactory, which can define a precision model in JTS.
    	 * @param factory
    	 * @return
    	 */
    	public Builder factory(GeometryFactory factory) {
    		this.factory = factory;
    		return this;
    	}
    	
    	/**
    	 * Build a new converter with the specified parameters.
    	 * @return
    	 */
    	public ConverterJTS build() {
    		return new ConverterJTS(factory, pixelWidth, pixelHeight, flatness);
    	}
    	
    }
    

    private ConverterJTS(final GeometryFactory factory, final double pixelWidth, final double pixelHeight, final double flatness) {
        this.factory = factory == null ? new GeometryFactory() : factory;
        this.flatness = flatness;
        this.pixelHeight = pixelHeight;
        this.pixelWidth = pixelWidth;
        if (pixelWidth != 1 && pixelHeight != 1)
            transform = AffineTransform.getScaleInstance(pixelWidth, pixelHeight);
    }

    /**
     * Convert a QuPath ROI to a JTS Geometry.
     * 
     * @param roi
     * @return
     */
    public Geometry roiToGeometry(ROI roi) {
    	if (roi instanceof PathPoints)
    		return pointsToGeometry((PathPoints)roi);
    	if (roi instanceof PathArea)
    		return areaToGeometry((PathArea)roi);
    	if (roi instanceof PathLine)
    		return lineToGeometry((PathLine)roi);
    	throw new UnsupportedOperationException("Unknown ROI " + roi + " - cannot convert to a Geometry!");
    }
    
    private Geometry lineToGeometry(PathLine roi) {
    	var coords = roi.getPolygonPoints().stream().map(p -> new Coordinate(p.getX(), p.getY())).toArray(Coordinate[]::new);
    	return factory.createLineString(coords);
    }
    
    private Geometry areaToGeometry(PathArea roi) {
    	if (roi.isEmpty())
    		return factory.createPolygon();
    	
    	Area shape = RoiTools.getArea(roi);
    	Geometry geometry = null;
    	if (shape.isSingular()) {
        	PathIterator iterator = shape.getPathIterator(transform, flatness);
        	geometry = getShapeReader().read(iterator);
    	} else {
    		geometry = convertAreaToGeometry(shape, transform, flatness, factory);
    	}
    	// Use simplifier to ensure a valid geometry
    	return VWSimplifier.simplify(geometry, 0);    		
    }
    
    
    /**
     * Convert a java.awt.geom.Area to a JTS Geometry, trying to correctly distinguish holes.
     * 
     * @param area
     * @param transform
     * @param flatness
     * @param factory
     * @return
     */
    private static Geometry convertAreaToGeometry(final Area area, final AffineTransform transform, final double flatness, final GeometryFactory factory) {

		List<Geometry> positive = new ArrayList<>();
		List<Geometry> negative = new ArrayList<>();

		PathIterator iter = area.getPathIterator(transform, flatness);

		CoordinateList points = new CoordinateList();
		
		double areaTempSigned = 0;
		double areaCached = 0;

		double[] seg = new double[6];
		double startX = Double.NaN, startY = Double.NaN;
		double x0 = 0, y0 = 0, x1 = 0, y1 = 0;
		boolean closed = false;
		while (!iter.isDone()) {
			switch(iter.currentSegment(seg)) {
			case PathIterator.SEG_MOVETO:
				// Log starting positions - need them again for closing the path
				startX = seg[0];
				startY = seg[1];
				x0 = startX;
				y0 = startY;
				iter.next();
				areaCached += areaTempSigned;
				areaTempSigned = 0;
				points.clear();
				points.add(new Coordinate(startX, startY));
				closed = false;
				continue;
			case PathIterator.SEG_CLOSE:
				x1 = startX;
				y1 = startY;
				closed = true;
				break;
			case PathIterator.SEG_LINETO:
				x1 = seg[0];
				y1 = seg[1];
				points.add(new Coordinate(x1, y1));
				closed = false;
				break;
			default:
				// Shouldn't happen because of flattened PathIterator
				throw new RuntimeException("Invalid area computation!");
			};
			areaTempSigned += 0.5 * (x0 * y1 - x1 * y0);
			// Add polygon if it has just been closed
			if (closed) {
				points.closeRing();
				Coordinate[] coords = points.toCoordinateArray();
//				for (Coordinate c : coords)
//					model.makePrecise(c);
				
				// Need to ensure polygons are valid at this point
				// Sometimes, self-intersections can thwart validity
				Geometry polygon = factory.createPolygon(coords);
				TopologyValidationError error = new IsValidOp(polygon).getValidationError();
				if (error != null) {
					logger.debug("Invalid polygon detected! Attempting to correct {}", error.toString());
					double areaBefore = polygon.getArea();
					Geometry geom = GeometrySnapper.snapToSelf(polygon,
							GeometrySnapper.computeSizeBasedSnapTolerance(polygon),
							true);
					double areaAfter = geom.getArea();
					if (!GeneralTools.almostTheSame(areaBefore, areaAfter, 0.0001)) {
						logger.warn("Unable to fix geometry (area before: {}, area after: {}): {}", polygon);
						logger.warn("Will attempt to proceed using {}", geom);
					} else {
						logger.debug("Geometry fix looks ok (area before: {}, area after: {})", areaBefore, areaAfter);
					}
					polygon = geom;
				}
				if (areaTempSigned < 0)
					negative.add(polygon);
				else if (areaTempSigned > 0)
					positive.add(polygon);
				// Zero indicates the shape is empty...
			}
			// Update the coordinates
			x0 = x1;
			y0 = y1;
			iter.next();
		}
		// TODO: Can I count on outer polygons and holes always being either positive or negative?
		// Since I'm not sure, I decide here based on signed areas
		areaCached += areaTempSigned;
		List<Geometry> outer;
		List<Geometry> holes;
		if (areaCached < 0) {
			outer = negative;
			holes = positive;
		} else if (areaCached > 0) {
			outer = positive;
			holes = negative;
		} else {
			return factory.createPolygon();
		}
		Geometry geometry = UnaryUnionOp.union(outer);

		if (!holes.isEmpty()) {
			Geometry hole = UnaryUnionOp.union(holes);
			geometry = geometry.difference((Geometry)hole);
		}
		return geometry;
	}
    
    
    private Geometry pointsToGeometry(PathPoints points) {
    	var coords = points.getPointList().stream().map(p -> new Coordinate(p.getX(), p.getY())).toArray(Coordinate[]::new);
    	if (coords.length == 1)
    		return factory.createPoint(coords[0]);
    	return factory.createMultiPointFromCoords(coords);
    }


    private ShapeReader getShapeReader() {
        if (shapeReader == null)
            shapeReader = new ShapeReader(factory);
        return shapeReader;
    }


    private ShapeWriter getShapeWriter() {
        if (shapeWriter == null)
            shapeWriter = new ShapeWriter(new Transformer());
        return shapeWriter;
    }


//    private CoordinateSequence toCoordinates(PolygonROI roi) {
//        CoordinateList list = new CoordinateList();
//        for (Point2 p : roi.getPolygonPoints())
//            list.add(new Coordinate(p.getX() * pixelWidth, p.getY() * pixelHeight));
//        return new CoordinateArraySequence(list.toCoordinateArray());
//    }

    private Shape geometryToShape(Geometry geometry) {
        return getShapeWriter().toShape(geometry);
    }

    private ROI geometryToROI(Geometry geometry, ImagePlane plane) {
    	if (geometry instanceof Point) {
    		Coordinate coord = geometry.getCoordinate();
    		return ROIs.createPointsROI(coord.x, coord.y, plane);
    	} else if (geometry instanceof MultiPoint) {
    		Coordinate[] coords = geometry.getCoordinates();
    		List<Point2> points = Arrays.stream(coords).map(c -> new Point2(c.x, c.y)).collect(Collectors.toList());
    		return ROIs.createPointsROI(points, plane);
    	}
        return RoiTools.getShapeROI(geometryToShape(geometry), plane, flatness);
    }


    private class Transformer implements PointTransformation {

        @Override
        public void transform(Coordinate src, Point2D dest) {
            dest.setLocation(
                    src.x / pixelWidth,
                    src.y / pixelHeight);
        }

    }


}