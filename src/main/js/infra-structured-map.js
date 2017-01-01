/*
 * Copyright (c) 2016 Google, Inc.
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
(function(root) {
  function InfraStructuredMap(map) {
    this.map = map;

    const infoWindow = new google.maps.InfoWindow();

    this.map.showInfoWindow = function(content, position) {
      infoWindow.setContent(content);
      infoWindow.setPosition(position);
      infoWindow.open(this);
    };

    this.projectsById = new Map();
    this.modeIds = new PropertyIds(
      ['Pedestrian / Bike', 'Transit', 'Freight', 'Other']);
    this.statusIds = new PropertyIds(['completed', 'planned', 'eval']);
    this.timelineIds = new PropertyIds(['completed', 'now', 'soon', 'someday']);
    this.segments = [];
    this.placemarks = [];

    this.masks = new PropertyMasks();
    this.masks.mode.setEnabledAll(true);
    this.masks.status.setEnabledAll(true);
    this.masks.timeline.setEnabledAll(true);

    this.colorsByTitle = new Map();
    this.colorsByTitle.set('Pedestrian / Bike', 'rgb(1, 87, 155)');
    this.colorsByTitle.set('Freight', 'rgb(165, 39, 20)');
    this.colorsByTitle.set('Transit', 'rgb(15, 157, 88)');
    this.colorsByTitle.set('Other', 'rgb(230, 81, 0)');
  };

  InfraStructuredMap.prototype.addModePropertyToggle =
    function(checkbox, propertyId) {
    this.addPropertyToggle(checkbox, this.modeIds, propertyId, this.masks.mode);
  };

  InfraStructuredMap.prototype.addStatusPropertyToggle =
    function(checkbox, propertyId) {
      this.addPropertyToggle(
        checkbox, this.statusIds, propertyId, this.masks.status);
  };

  InfraStructuredMap.prototype.addTimelinePropertyToggle =
    function(checkbox, propertyId) {
      this.addPropertyToggle(
        checkbox, this.timelineIds, propertyId, this.masks.timeline);
  };

  InfraStructuredMap.prototype.addPropertyToggle =
    function(checkbox, propertyIds, propertyId, propertyMask) {
    const index = propertyIds.getIndex(propertyId);
    propertyMask.setEnabled(index, checkbox.checked);
    checkbox.addEventListener('change', function(event) {
      propertyMask.setEnabled(index, checkbox.checked);
      for (const segment of this.segments) {
        segment.updateChannels(this.masks);
      }
      for (const placemark of this.placemarks) {
        placemark.updateChannels(this.masks);
      }
    }.bind(this));
  };

  InfraStructuredMap.prototype.load = function(data) {
    this.projectsById.clear();

    // Build up a mapping of all projects
    for (const feature of data.features) {
      const projects = [];
      for (const project of feature.projects) {
        projects.push(this.createProjectRef(project));
      }
      this.projectsById.set(feature.id, projects);
    }

    this.segments = [];
    for (const segment of data.segments) {
      const channels = this.constructChannelsFromFeatureIds(segment.ids);
      const ms = new MapSegment(this.map, channels, segment);
      ms.updateChannels(this.masks);
      this.segments.push(ms);
    }

    this.placemarks = [];
    for (const placemark of data.placemarks) {
      const position = new google.maps.LatLng(placemark.lat, placemark.lng);
      const channels = this.constructChannelsFromFeatureIds(placemark.ids);
      const place = new MapPlacemark(this.map, channels, position);
      place.updateChannels(this.masks);
      this.placemarks.push(place);
    }
  };

  InfraStructuredMap.prototype.constructChannelsFromFeatureIds = function(ids) {
    const channels = [];
    const channelsByColor = new Map();
    for (const featureId of ids) {
      for (const project of this.projectsById.get(featureId)) {
        let channel = channelsByColor.get(project.color);
        if (!channel) {
          channel = new MapChannel(project.color);
          channelsByColor.set(project.color, channel);
          channels.push(channel);
        }
        channel.projectRefs.push(project);
      }
    }
    return channels;
  };

  InfraStructuredMap.prototype.createProjectRef = function(project) {
    const masks = new PropertyMasks();
    masks.mode.setEnabled(this.modeIds.getIndex(project.title[0]), true);
    masks.status.setEnabled(this.statusIds.getIndex(project.status), true);
    if (project.timeline) {
      masks.timeline.setEnabled(
        this.timelineIds.getIndex(project.timeline), true);
    } else {
      masks.timeline.setEnabledAll(true);
    }
    let color = this.colorsByTitle.get(project.title[0]);
    if (project.color) {
      color = project.color;
    }
    return new ProjectRef(masks, project.title, color);
  };

  root.InfraStructuredMap = InfraStructuredMap;


  function MapFeature(map, channels) {
    this.map = map;
    this.channels = channels;
    this.channelMask = [];
    this.activeChannelCount = 0;
    this.masks = new PropertyMasks();
    return this;
  }

  MapFeature.prototype.updateChannelMask = function(propertyMasks) {
    this.masks = propertyMasks;
    const newMask = [];
    let activeChannelCount = 0;
    for (const channel of this.channels) {
      const active = channel.projectRefs.isActive(propertyMasks);
      newMask.push(active);
      if (active) {
        activeChannelCount++;
      }
    }
    if (channelMasksAreEqual(this.channelMask, newMask)) {
      return false;
    }
    this.channelMask = newMask;
    this.activeChannelCount = activeChannelCount;
    return true;
  };

  MapFeature.prototype.handleClick_ = function(event) {
    let content = '';
    for (let index = 0; index < this.channels.length; ++index) {
      if (!this.channelMask[index]) {
        continue;
      }
      const channel = this.channels[index];
      for (const projectRef of channel.projectRefs.refs) {
        if (projectRef.propertyMasks.isActive(this.masks)) {
          content += projectRef.titles.join(' - ') + ' <br/>\n';
        }
      }
    }
    this.map.showInfoWindow(content, event.latLng);
  };

  function channelMasksAreEqual(lhs, rhs) {
    if (lhs.length != rhs.length) {
      return false;
    }
    for (let i = 0; i < lhs.length; ++i) {
      if (lhs[i] != rhs[i]) {
        return false;
      }
    }
    return true;
  };

  function MapSegment(map, channels, segment) {
    MapFeature.call(this, map, channels);

    const path = google.maps.geometry.encoding.decodePath(segment.line);
    this.options = {
      path: path,
      strokeOpacity: 0,
      icons: [],
      map: map,
      clickable: true,
    };
    this.polyline = new google.maps.Polyline(this.options);
    google.maps.event.addListener(
      this.polyline, 'click', this.handleClick_.bind(this));
  };

  MapSegment.prototype = new MapFeature();

  MapSegment.prototype.updateChannels = function(propertyMasks) {
    if (!this.updateChannelMask(propertyMasks)) {
      return;
    }
    let offset = -(this.activeChannelCount - 1) / 2;
    const icons = [];
    for (let index = 0; index < this.channels.length; ++index) {
      if (!this.channelMask[index]) {
        continue;
      }
      const channel = this.channels[index];
      const symbol = {
        path: 'M ' + offset + ',-0.2 ' + offset + ',0',
        strokeOpacity: 1,
        strokeColor: channel.color,
        scale: 5,
      };
      icons.push({icon: symbol, offset: '0', repeat: '2px'});
      offset++;
    }
    this.options.icons = icons;
    this.polyline.setOptions(this.options);
  };

  function MapPlacemark(map, channels, position) {
    MapFeature.call(this, map, channels);
    this.position = position;
    this.markers = [];
    return this;
  };

  MapPlacemark.prototype = new MapFeature();

  function circlePath(r) {
    return `m -${r},0 a ${r},${r} 0 1,0 ${2*r},0 a ${r},${r} 0 1,0 -${2*r},0`;
  };

  MapPlacemark.prototype.updateChannels = function(propertyMasks) {
    if (!this.updateChannelMask(propertyMasks)) {
      return;
    }

    // Clear any existing markers
    for (const marker of this.markers) {
      marker.setMap(null);
    }
    this.markers = [];

    let offset = 0;
    for (let index = 0; index < this.channels.length; ++index) {
      if (!this.channelMask[index]) {
        continue;
      }
      const channel = this.channels[index];

      const circle = {
        path: 'M ' + offset + ',0 ' + circlePath(10),
        fillColor: channel.color,
        fillOpacity: 1.0,
        scale: 1,
        strokeColor: 'white',
        strokeWeight: 2,
      };
      const options = {
        position: this.position,
        icon: circle,
        map: this.map,
        clickable: true,
      };
      const marker = new google.maps.Marker(options);
      this.markers.push(marker);
      offset += 10;

      google.maps.event.addListener(
        marker, 'click', this.handleClick_.bind(this));
    }
  };

  function MapChannel(color) {
    this.projectRefs = new ProjectRefs();
    this.color = color;
    return this;
  };

  function ProjectRef(propertyMasks, titles, color) {
    this.propertyMasks = propertyMasks;
    this.titles = titles;
    this.color = color;
    return this;
  };

  function ProjectRefs() {
    this.refs = [];
  };

  ProjectRefs.prototype.push = function(projectRef) {
    this.refs.push(projectRef);
  };

  ProjectRefs.prototype.isActive = function(propertyMasks) {
    for (const projectRef of this.refs) {
      if (projectRef.propertyMasks.isActive(propertyMasks)) {
        return true;
      }
    }
    return false;
  };

  function PropertyIds(propertyIds) {
    this.idToIndex = new Map();
    for (const propertyId of propertyIds) {
      this.idToIndex.set(propertyId, this.idToIndex.size);
    }
    return this;
  }

  PropertyIds.prototype.getIndex = function(id) {
    return this.idToIndex.get(id);
  };

  function PropertyMask() {
    this.mask = 0;
    return this;
  };

  PropertyMask.prototype.isActive = function(rhs) {
    return (this.mask & rhs.mask) != 0;
  };

  PropertyMask.prototype.setEnabled = function(index, enabled) {
    if (enabled) {
      this.mask |= 1 << index;
    } else {
      this.mask &= ~(1 << index);
    }
  };

  PropertyMask.prototype.setEnabledAll = function(enabled) {
    if (enabled) {
      this.mask = ~0;
    } else {
      this.mask = 0;
    }
  };

  PropertyMask.prototype.isEnabled = function(index) {
    return (this.mask >> index) & 1;
  };

  PropertyMask.prototype.toString = function() {
    return (this.mask >>> 0).toString(2);
  };

  function PropertyMasks() {
    this.mode = new PropertyMask();
    this.status = new PropertyMask();
    this.timeline = new PropertyMask();
    return this;
  };

  PropertyMasks.prototype.isActive = function(rhs) {
    return this.mode.isActive(rhs.mode)
      && this.status.isActive(rhs.status)
      && this.timeline.isActive(rhs.timeline);
  };

  PropertyMasks.prototype.toString = function() {
    return 'mode: ' + this.mode.toString()
      + ' status: ' + this.status.toString()
      + ' time: ' + this.timeline.toString();
  };
}(this));
