/**
 * Copyright (C) 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.infrastructuredmap;

import com.google.infrastructuredmap.PolylineAlignmentGraph.Polyline;
import com.google.infrastructuredmap.model.MapData;
import com.google.infrastructuredmap.model.MapFeature;
import com.google.infrastructuredmap.model.MapPlacemark;
import com.google.infrastructuredmap.model.MapSegment;
import com.google.maps.model.EncodedPolyline;
import com.google.maps.model.LatLng;
import com.vividsolutions.jts.geom.Coordinate;
import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Feature;
import de.micromata.opengis.kml.v_2_2_0.Folder;
import de.micromata.opengis.kml.v_2_2_0.Geometry;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.LineString;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.NoninvertibleTransformException;
import org.opengis.referencing.operation.TransformException;

/**
 * Extract {@link MapSegment} and {@link MapPlacemark} features from a KML
 * document, aligning overlapping polyline segments and placemark points into
 * combined features.
 */
public class MapDataExtractor {
  // Transform from WGS84 (aka lat-lng) into UTM zone 10N (aka Washington State).
  private static final MathTransform _transform = createTransform();
  private static final MathTransform _reverseTransform = reverseTransform(_transform);
  
  public static MapData extractMapData(Kml kml) {
    MapDataExtractor extractor = new MapDataExtractor();
    return extractor.run(kml);
  }
  
  private final PolylineAlignmentGraph _graph;
  private final PlacemarkAlignment _placemarks;
  private final MapData _data;
  
  private MapDataExtractor() {
    _graph = new PolylineAlignmentGraph();
    _placemarks = new PlacemarkAlignment();
    _data = new MapData();
  }

  private MapData run(Kml kml) {    
    try {
      visitFeature(kml.getFeature());
    } catch (IllegalStateException ex) {
      ex.printStackTrace();
    }

    for (Polyline p : _graph.go()) {
      MapSegment segment = new MapSegment();
      segment.ids = new ArrayList<>();
      for (Placemark placemark : p.placemarks) {
        segment.ids.add(placemark.getName());
      }
      segment.line = encodePolyline(p.line);
      _data.segments.add(segment);
    }
    
    for (Map.Entry<Coordinate, List<String>> entry : _placemarks.getEntries()) {
      de.micromata.opengis.kml.v_2_2_0.Coordinate c = reverse(entry.getKey());
      MapPlacemark placemark = new MapPlacemark();
      placemark.lat = c.getLatitude();
      placemark.lng = c.getLongitude();
      placemark.ids = entry.getValue();
      _data.placemarks.add(placemark);
    }
    return _data;
  }

  private String encodePolyline(List<Coordinate> line) {
    List<LatLng> latLngs = new ArrayList<>();
    for (Coordinate c : line) {
      de.micromata.opengis.kml.v_2_2_0.Coordinate p = reverse(c);
      latLngs.add(new LatLng(p.getLatitude(), p.getLongitude()));
    }
    return new EncodedPolyline(latLngs).getEncodedPath();
  }

  private void visitFeature(Feature feature) {
    if (feature instanceof Document) {
      Document doc = (Document) feature;
      for (Feature child : doc.getFeature()) {
        visitFeature(child);
      }
    } else if (feature instanceof Folder) {
      Folder folder = (Folder) feature;
      for (Feature child : folder.getFeature()) {
        visitFeature(child);
      }
    } else if (feature instanceof Placemark) {
      processPlacemark((Placemark) feature);
    } else {
      System.err.println("Unknown feature: " + feature);
    }
  }

  private void processPlacemark(Placemark placemark) {
    Geometry geometry = placemark.getGeometry();
    if (geometry instanceof LineString) {
      Coordinate prev = null;
      for (Coordinate c : convertCoordinates(((LineString) geometry).getCoordinates())) {
        c = _graph.snapToGraph(c, prev);
        if (prev != null) {
          _graph.addEdge(prev, c, placemark);
        }
        prev = c;
      }
    } else if (geometry instanceof  Point) {
      List<Coordinate> coordinates = convertCoordinates(((Point) geometry).getCoordinates());
      if (coordinates.size() != 1) {
        throw new IllegalStateException();
      }
      _placemarks.addPlacemark(coordinates.get(0), placemark.getName());
    }
    MapFeature feature = new MapFeature();
    feature.id = placemark.getName();
    _data.features.add(feature);
  }

  private List<Coordinate> convertCoordinates(List<de.micromata.opengis.kml.v_2_2_0.Coordinate> kmlCoordinates) {
    List<Coordinate> coordinates = new ArrayList<>();
    for (de.micromata.opengis.kml.v_2_2_0.Coordinate raw : kmlCoordinates) {
      coordinates.add(transform(raw));
    }
    if (coordinates.size() < 2) {
      return coordinates;
    }
    Coordinate a = coordinates.get(0);
    Coordinate b = coordinates.get(coordinates.size() - 1);
    if (Math.atan2(b.y - a.y, b.x - a.x) < 0) {
      Collections.reverse(coordinates);
    }
    return coordinates;
  }

  private Coordinate transform(de.micromata.opengis.kml.v_2_2_0.Coordinate c) {
    Coordinate source = new Coordinate();
    source.x = c.getLongitude();
    source.y = c.getLatitude();
    Coordinate dest = new Coordinate();
    try {
      JTS.transform(source, dest, _transform);
      return dest;
    } catch (TransformException e) {
      throw new IllegalStateException(e);
    }
  }

  private de.micromata.opengis.kml.v_2_2_0.Coordinate reverse(Coordinate c) {
    return reverse(_reverseTransform, c);
  }
  
  public static de.micromata.opengis.kml.v_2_2_0.Coordinate reverse(MathTransform reverseTransform, Coordinate c) {
    Coordinate dest = new Coordinate();
    try {
      JTS.transform(c, dest, reverseTransform);
      return new de.micromata.opengis.kml.v_2_2_0.Coordinate(dest.x, dest.y);
    } catch (TransformException e) {
      throw new IllegalStateException(e);
    }
  }
  

  private static MathTransform createTransform() {
    try {
      CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:4326");
      CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:32610");
      return CRS.findMathTransform(sourceCRS, targetCRS, false);
    } catch (FactoryException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static MathTransform reverseTransform(MathTransform transform) {
    try {
      return _transform.inverse();
    } catch (NoninvertibleTransformException e) {
      throw new IllegalStateException(e);
    }
  }
}
