infra-structured-map is a tool for creating interactive maps of capital
infrastructure projects in a given area.  The tool consumes KML and Markdown to
produce a web-based map.

This is not an official Google product.

## Usage

Document your projects in a Markdown file:

```markdown
# The Bigger Dig
<!-- id: ProjectId status: planned timeline: soon -->

This is a multi-century project to build a subway from New York to LA.
```

Next, define the geographic features of each project in KML:

```xml
<kml>
  ...
  <Placemark>
    <name>ProjectId</name>
    <LineString>...</LineString>
  </Placemark>
  ...
</kml>
```

Tip: [Google My Maps](https://www.google.com/mymaps) is a great tool for
maintaining your map data.  It exports KML and you can even pass your KML
download URL to the extract below.

Next, build a JSON data file for powering your interactive map.

```
java com.google.infrastructuredmap.MapAndMarkdownExtractorMain \
 -markdown project.md \
 -kml project.kml \
 -output output.js
```

Finally, display your data on a webpage:

```javascript
var map = new google.maps.Map(element, options);
var infraMap = new InfraStructuredMap(map);
infraMap.load(data);
```
