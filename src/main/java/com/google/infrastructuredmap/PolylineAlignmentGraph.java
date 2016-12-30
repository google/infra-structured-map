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

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineSegment;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Graph of polyline nodes and edges to "align" similar polyline into combined segments.
 */
public class PolylineAlignmentGraph {
  
  private static final double SNAP_THRESHOLD_METERS = 15.0;
  
  private Map<Coordinate, Node> nodes = new HashMap<>();

  public PolylineAlignmentGraph() {}

  public void addEdge(Coordinate from, Coordinate to, Placemark placemark) {
    if (from.equals(to)) {
      throw new IllegalStateException("bad edge: from=" + from + " to=" + to);
    }
    // If there isn't already an existing edge...
    if (!nodes.get(from).edges.containsKey(to)) {
      if (splitEdgeAtIntermediatePoint(from, to, placemark)) {
        return;
      }
      if (splitEdgeAtIntersectingEdge(from, to, placemark)) {
        return;
      }
    }
    addEdgeInternal(from, to, placemark);
  }

  private boolean splitEdgeAtIntermediatePoint(Coordinate from, Coordinate to, Placemark placemark) {
    LineSegment segment = new LineSegment(from, to);
    double minValue = SNAP_THRESHOLD_METERS;
    Entry<Coordinate, Node> minEntry = null;
    for (Map.Entry<Coordinate, Node> nodeEntry : nodes.entrySet()) {
      Coordinate edgePoint = segment.closestPoint(nodeEntry.getKey());
      if (edgePoint.equals2D(segment.p0) || edgePoint.equals2D(segment.p1)) {
        continue;
      }
      double d = edgePoint.distance(nodeEntry.getKey());
      if (d < minValue) {
        minValue = d;
        minEntry = nodeEntry;
      }
    }
    if (minEntry != null) {
      addEdge(from, minEntry.getKey(), placemark);
      addEdge(minEntry.getKey(), to, placemark);
      return true;
    }
    return false;
  }
  
  private boolean splitEdgeAtIntersectingEdge(Coordinate from, Coordinate to, Placemark placemark) {
    LineSegment segment = new LineSegment(from, to);
    for (Map.Entry<Coordinate, Node> nodeEntry : nodes.entrySet()) {
      for (Map.Entry<Coordinate, Edge> edgeEntry : nodeEntry.getValue().edges.entrySet()) {
        LineSegment edgeSegment = new LineSegment(nodeEntry.getKey(), edgeEntry.getKey());
        Coordinate point = segment.intersection(edgeSegment);
        if (point != null && isOk(edgeSegment, point) && isOk(segment, point)) {
          splitEdge(edgeSegment.p0, edgeSegment.p1, point);
          addEdge(from, point, placemark);
          addEdge(point, to, placemark);
          return true;
        }
      }
    }
    return false;
  }

  private boolean isOk(LineSegment edge, Coordinate point) {
    return edge.p0.distance(point) > SNAP_THRESHOLD_METERS && edge.p1.distance(point) > SNAP_THRESHOLD_METERS;
  }

  private void addEdgeInternal(Coordinate from, Coordinate to, Placemark placemark) {
    addEdgeDirectional(from, to, placemark, Direction.FORWARD);
    addEdgeDirectional(to, from, placemark, Direction.REVERSE);
  }
  
  private enum Direction {
    FORWARD, REVERSE
  }

  private void addEdgeDirectional(Coordinate from, Coordinate to, Placemark placemark, Direction direction) {
    Node node = nodes.get(from);
    Edge edge = node.edges.get(to);
    if (edge == null) {
      edge = new Edge();
      node.edges.put(to, edge);
    }
    if (direction == Direction.FORWARD) {
      edge.placemarks.addLast(placemark);
    } else {
      edge.placemarks.addFirst(placemark);
    }
  }
  
  public Coordinate snapToGraph(Coordinate point, @Nullable Coordinate previous) {
    Coordinate node = snapToNode(point, previous);
    if (node != null) {
      return node;
    }
    
    node = snapToEdge(point, previous);
    if (node != null) {
      return node;
    }
    
    nodes.put(point, new Node());
    return point;
  }
  
  private Coordinate snapToNode(Coordinate point, @Nullable Coordinate previous) {
    // Try snapping to a node.
    double minDistance = SNAP_THRESHOLD_METERS;
    Coordinate minPoint = null;
    for (Coordinate nodePoint : nodes.keySet()) {
      if (nodePoint.equals(previous)) {
        continue;
      }
      double d = point.distance(nodePoint);
      if (d < minDistance) {
        minDistance = d;
        minPoint = nodePoint;
      }
    }
    return minPoint;
  }
  
  private Coordinate snapToEdge(Coordinate point, @Nullable Coordinate previous) {
    double minDistance = SNAP_THRESHOLD_METERS;
    
    Coordinate minFromPoint = null;
    Coordinate minToPoint = null;
    Coordinate minSnappedPoint = null;
    
    for (Map.Entry<Coordinate, Node> entry : nodes.entrySet()) {
      Coordinate nodePoint = entry.getKey();
      Node node = entry.getValue();
      for (Coordinate edge : node.edges.keySet()) {
        LineSegment segment = new LineSegment(nodePoint, edge);
        Coordinate snapped = segment.closestPoint(point);
        if (snapped.equals(previous)) {
          continue;
        }
        double d = point.distance(snapped);
        if (d < minDistance) {
          minDistance = d;
          minFromPoint = nodePoint;
          minToPoint = edge;
          minSnappedPoint = snapped;
        }        
      }
    }
    
    if (minSnappedPoint == null) {
      return null;
    }
    
    splitEdge(minFromPoint, minToPoint, minSnappedPoint);
    
    return minSnappedPoint;
  }

  private void splitEdge(Coordinate fromPoint, Coordinate toPoint, Coordinate midPoint) {
    Node minFromNode = nodes.get(fromPoint);
    Node toNode = nodes.get(toPoint);
    
    Edge existingToEdge = minFromNode.edges.remove(toPoint);
    minFromNode.edges.put(midPoint, existingToEdge);
    
    Edge existingFromEdge = toNode.edges.remove(fromPoint);
    toNode.edges.put(midPoint, existingFromEdge);
    
    Node mid = new Node();
    mid.edges.put(fromPoint, new Edge(existingFromEdge));
    mid.edges.put(toPoint, new Edge(existingToEdge));
    nodes.put(midPoint, mid);
  }
  
  private class Node {
    private Map<Coordinate, Edge> edges = new HashMap<>();
  }
  
  private class Edge {
    public Edge() {}
    public Edge(Edge existing) {
      placemarks.addAll(existing.placemarks);
    }

    private Deque<Placemark> placemarks = new LinkedList<>();
  }
  
  public class Polyline {
    public Polyline(List<Coordinate> line, Collection<Placemark> placemarks) {
      this.line = line;
      this.placemarks = placemarks;
    }
    List<Coordinate> line;
    Collection<Placemark> placemarks;
  }
  
  public List<Polyline> go() {
    List<Polyline> polylines = new ArrayList<>();
    Set<LineSegment> visitedEdges = new HashSet<>();
    
    int edgeCount = 0;
    for (Map.Entry<Coordinate, Node> nodeEntry : nodes.entrySet()) {
      for (Map.Entry<Coordinate, Edge> edgeEntry : nodeEntry.getValue().edges.entrySet()) {
        edgeCount++;
        LineSegment segment = asSegment(nodeEntry.getKey(), edgeEntry.getKey());
        if (!visitedEdges.contains(segment)) {
          List<Coordinate> c = new ArrayList<>();
          Deque<Placemark> placemarks = edgeEntry.getValue().placemarks;
          if (!hasMatchingOutgoing(nodeEntry.getValue(), edgeEntry.getValue())) {
            exploreEdgesWithSamePlacemarks(nodeEntry.getKey(), placemarks, c, visitedEdges);
            polylines.add(new Polyline(c, placemarks));
          }
        }
      }
    }
    if (edgeCount / 2 != visitedEdges.size()) {
      //throw new IllegalStateException("expected=" + (edgeCount/2) + " actual=" + visitedEdges.size());
    }
    return polylines;
  }

  private boolean hasMatchingOutgoing(Node node, Edge sourceEdge) {
    for (Edge edge : node.edges.values()) {
      if (edge == sourceEdge) {
        continue;
      }
      if (edge.placemarks.size() != sourceEdge.placemarks.size()) {
        continue;
      }
      Iterator<Placemark> a = edge.placemarks.iterator();
      Iterator<Placemark> b = sourceEdge.placemarks.descendingIterator();
      if (equals(a, b)) {
        return true;
      }
    }
    return false;
  }

  private boolean equals(Iterator<Placemark> lhs, Iterator<Placemark> rhs) {
    while (lhs.hasNext() && rhs.hasNext()) {
      if (!Objects.equals(lhs.next(), rhs.next())) {
        return false;
      }
    }
    return !lhs.hasNext() && !rhs.hasNext();
  }

  private void exploreEdgesWithSamePlacemarks(Coordinate nodePoint, Collection<Placemark> placemarks,
      List<Coordinate> outputPoints, Set<LineSegment> visitedEdges) {
    Coordinate prevPoint = null;
    while (nodePoint != null) {
      outputPoints.add(nodePoint);
      Node node = nodes.get(nodePoint);
      Coordinate nextPoint = null;
      for (Map.Entry<Coordinate, Edge> edgeEntry : node.edges.entrySet()) {
        if (edgeEntry.getValue().placemarks.equals(placemarks) && !edgeEntry.getKey().equals(prevPoint)) {
          LineSegment segment = asSegment(nodePoint, edgeEntry.getKey());
          if (!visitedEdges.add(segment)) {
            continue;
          }          
          prevPoint = nodePoint;
          nextPoint = edgeEntry.getKey();
          break;
        }
      }
      nodePoint = nextPoint;
    }
  }

  public Set<LineSegment> dump() {
    Set<LineSegment> visitedEdges = new HashSet<>();
    for (Map.Entry<Coordinate, Node> nodeEntry : nodes.entrySet()) {
      for (Map.Entry<Coordinate, Edge> edgeEntry : nodeEntry.getValue().edges.entrySet()) {
        LineSegment segment = asSegment(nodeEntry.getKey(), edgeEntry.getKey());
        visitedEdges.add(segment);
      }
    }
    return visitedEdges;
  }
  
  private LineSegment asSegment(Coordinate a, Coordinate b) {
    if (a.compareTo(b) < 0) {
      return new LineSegment(a, b);
    } else {
      return new LineSegment(b, a);
    }
  }
}
