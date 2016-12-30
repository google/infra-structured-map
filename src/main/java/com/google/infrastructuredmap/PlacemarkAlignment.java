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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs snap-to alignment of point features with similar locations.
 */
public class PlacemarkAlignment {
  
  private static final double SNAP_THRESHOLD_METERS = 20.0;
  
  private Map<Coordinate, List<String>> _placemarksByLocation = new HashMap<>();
  
  public Iterable<Map.Entry<Coordinate, List<String>>> getEntries() {
    return _placemarksByLocation.entrySet();
  }
  
  public void addPlacemark(Coordinate location, String id) {
    for (Map.Entry<Coordinate, List<String>> entry : _placemarksByLocation.entrySet()) {
      if (entry.getKey().distance(location) < SNAP_THRESHOLD_METERS) {
        entry.getValue().add(id);
        return;
      }
    }
    List<String> ids = new ArrayList<>();
    ids.add(id);
    _placemarksByLocation.put(location, ids);
  }
}
