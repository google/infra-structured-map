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

import com.google.infrastructuredmap.model.ProjectReference;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pegdown.PegDownProcessor;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.HtmlBlockNode;
import org.pegdown.ast.InlineHtmlNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.SuperNode;
import org.pegdown.ast.TextNode;

/**
 * Extract annotated project reference comments from a Markdown document to
 * produce a mapping of {@link ProjectReference}.
 */
public class MarkdownReferenceExtractor {

  private static Pattern _metadataPattern = Pattern.compile("<!--(.*)-->", Pattern.DOTALL);

  private MarkdownReferenceExtractor() {
  }

  private Deque<String> _headers = new ArrayDeque<>();
  private Map<String, List<ProjectReference>> _referencesById = new HashMap<>();

  /**
   * Returns a mapping of {@link ProjectReference}, grouped by project id.
   */
  public static Map<String, List<ProjectReference>> extractReferences(Path path) throws IOException {
    CharBuffer decode = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(Files.readAllBytes(path)));
    PegDownProcessor processor = new PegDownProcessor();
    RootNode node = processor.parseMarkdown(decode.array());

    MarkdownReferenceExtractor extractor = new MarkdownReferenceExtractor();
    extractor.visit(node, "");
    return extractor._referencesById;
  }

  private void visit(Node node, String prefix) {
    if (node instanceof HeaderNode) {
      HeaderNode header = (HeaderNode) node;
      while (_headers.size() >= header.getLevel()) {
        _headers.pop();
      }
      _headers.push(toString(node, new StringBuilder()).toString());
    } else if (node instanceof HtmlBlockNode || node instanceof InlineHtmlNode) {
      TextNode block = (TextNode) node;
      Matcher m = _metadataPattern.matcher(block.getText());
      if (m.matches()) {
        Map<String, String> metadata = extractMetadata(m.group(1));
        ProjectReference reference = new ProjectReference();
        String id = metadata.get("id");
        reference.status = metadata.get("status");
        reference.timeline = metadata.get("timeline");
        if ("completed".equals(reference.status) && reference.timeline == null) {
          reference.timeline = "completed";
        }
        
        if (metadata.containsKey("color")) {
          reference.color = metadata.get("color");
        }
        reference.title = new ArrayList<>(_headers);
        Collections.reverse(reference.title);
        if (metadata.containsKey("label")) {
          reference.title.add(metadata.get("label"));
        }
        validate(id, reference);
        
        List<ProjectReference> references = _referencesById.get(id);
        if (references == null) {
          references = new ArrayList<>();
          _referencesById.put(id, references);
        }
        references.add(reference);
      } else {
        throw new IllegalStateException("content=" + block.getText());
      }
    } else {
      for (Node child : node.getChildren()) {
        visit(child, prefix + "  ");
      }
    }
  }
  
  private enum Status { COMPLETED, PLANNED, EVAL }
  private enum Timeline { COMPLETED, NOW, SOON, SOMEDAY };
  
  private class ValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ValidationException(String id, String message) {
      super("id=" + id + " msg=" + message);
    }
  }

  private void validate(String id, ProjectReference reference) {
    if (reference.status == null) {
      throw new ValidationException(id, "missing \"status\" property");
    }
    validateEnum(id, reference.status, Status.values());
    if (reference.timeline != null) {
      validateEnum(id, reference.timeline, Timeline.values());
    }
  }

  private <T extends Enum<T>> void  validateEnum(String id, String value, T[] values) {
    for (T enumValue : values) {
      if (enumValue.name().toLowerCase().equals(value)) {
        return;
      }
    }
    throw new ValidationException(id, "unknown property value=" + value);
  }

  private static StringBuilder toString(Node node, StringBuilder b) {
    if (node instanceof TextNode) {
      TextNode textNode = (TextNode) node;
      b.append(textNode.getText());
    } else if (node instanceof HeaderNode || node instanceof SuperNode) {
      for (Node child : node.getChildren()) {
        toString(child, b);
      }
    } else {
      b.append(node);
    }
    return b;
  }

  private Map<String, String> extractMetadata(String text) {
    Map<String, String> metadata = new HashMap<>();
    String splitOn = text.contains("\n") ? "\n" : ",";
    for (String kvp : text.split(splitOn)) {
      if (kvp.trim().isEmpty()) {
        continue;
      }
      String[] keyAndValue = kvp.split(":");
      if (keyAndValue.length != 2) {
        throw new IllegalStateException("Invalid kvp: " + kvp);
      }
      String key = keyAndValue[0].trim();
      String value = keyAndValue[1].trim();
      metadata.put(key, value);
    }
    return metadata;
  }
}
