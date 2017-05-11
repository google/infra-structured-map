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
package com.google.infrastructuredmap.model;

import java.util.List;

public class ProjectReference {
  public List<String> title;

  /**
   * The HTML element id for the heading title element that should be referenced
   * when linking to this project.
   */
  public String headingId;
  
  public String timeline;
  public String status;
  public String color;
}
