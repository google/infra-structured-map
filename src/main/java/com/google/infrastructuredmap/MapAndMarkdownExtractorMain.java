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

import com.google.gson.Gson;
import com.google.infrastructuredmap.model.MapData;
import com.google.infrastructuredmap.model.MapFeature;
import com.google.infrastructuredmap.model.ProjectReference;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * Application to process linked data from a KML map and a Markdown document
 * into a shared data model, exported to JSON.
 */
public class MapAndMarkdownExtractorMain {
  private static final String ARG_KML = "kml";
  private static final String ARG_MARKDOWN = "markdown";
  private static final String ARG_JSON_OUTPUT = "output";
  private static final String ARG_JSONP = "jsonp";

  public static void main(String[] args) throws IOException, ParseException {

    Options options = new Options();
    options.addOption(ARG_KML, true, "path to KML input");
    options.addOption(ARG_MARKDOWN, true, "path to Markdown input");
    options.addOption(ARG_JSON_OUTPUT, true, "path to write json output");
    options.addOption(ARG_JSONP, true, "JSONP template to wrap output JSON data");

    CommandLineParser parser = new DefaultParser();
    CommandLine cli = parser.parse(options, args);

    // Extract map features from the input KML.
    MapData data = null;
    try (InputStream in = openStream(cli.getOptionValue(ARG_KML))) {
      Kml kml = Kml.unmarshal(in);
      data = MapDataExtractor.extractMapData(kml);
    }

    // Extract project features from the input Markdown.
    Map<String, List<ProjectReference>> references = MarkdownReferenceExtractor
        .extractReferences(Paths.get(cli.getOptionValue(ARG_MARKDOWN)));
    for (MapFeature feature : data.features) {
      List<ProjectReference> referencesForId = references.get(feature.id);
      if (referencesForId == null) {
        throw new IllegalStateException("Unknown project reference: " + feature.id);
      }
      feature.projects = referencesForId;
    }

    // Write the resulting data to the output path.
    Gson gson = new Gson();
    try (FileWriter out = new FileWriter(cli.getOptionValue(ARG_JSON_OUTPUT))) {
      String json = gson.toJson(data);
      if (cli.hasOption(ARG_JSONP)) {
        json = String.format(cli.getOptionValue(ARG_JSONP), json);
      }
      out.write(json);
    }
  }

  private static InputStream openStream(String path) throws IOException {
    if (path.startsWith("http:") || path.startsWith("https:")) {
      URL url = new URL(path);
      return new BufferedInputStream(url.openStream());
    }
    return new BufferedInputStream(new FileInputStream(path));
  }
}
