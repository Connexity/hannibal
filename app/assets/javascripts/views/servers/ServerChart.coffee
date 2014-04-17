# Copyright 2013 Sentric. See LICENSE for details.

class StackingToggleView extends Backbone.View
  events:
    "click .stacking-options .enable": -> @toggle(true)
    "click .stacking-options .disable": -> @toggle(false)

  initialize: ->
    @graph = @options.graph
    @enabled = !@graph.renderer.unstack

    @render()

  toggle: (flag) ->
    @enabled = flag
    @graph.renderer.unstack = !flag
    @graph.update()
    @render()

  render: ->
    @$el.html """
      <ul class="stacking-options">
        <li class="enable #{if @enabled then "checked" else ""}">Stacked</li>
        <li class="disable #{if !@enabled then "checked" else ""}">Grouped</li>
      </ul>
    """

class PlotByView extends Backbone.View
  events:
    "click ul.field-options li": (evnt) -> @plotBy(evnt)

  initialize: ->
    @parentView = @options.parentView
    @graph = @options.graph
    @render()

  plotBy: (evnt) ->
    $element = $(evnt.target)
    plotBy = $element.data('field')

    @$("ul.field-options li").each(() ->
      $this = $(@)
      if($this.data('field') == plotBy)
        $this.addClass('checked')
      else
        $this.removeClass('checked')
    )

    @parentView.setPlotByField(plotBy)
    @parentView.render()


  render: ->
    @$el.html """
      <ul class="field-options">
        <li class="checked" data-field="storefileSizeMB">Size (MB)</li>
        <li data-field="totalReadRate">Read Req. / sec</li>
        <li data-field="totalWriteRate">Write Req. / sec</li>
      </ul>
    """

class @ServerChartView extends  AbstractServerChartView

  initialize: ->
    super

    @collection.on "reset", _.bind(@render, @)
    @on "table:click", _.bind(@onTableClick, @)
    @setPlotByField('storefileSizeMB')

  setPlotByField: (attrib) ->
    @plotByField = attrib

  getChartSeries: ->
    hostNames = @hostNames
    hostNameMap = @hostNameMap

    if hostNames.length == 1
      hostNames.push ""
      hostNameMap[""] = 1

    series = for own tableName, regionInfos of @regionsByTable
      groupedByHost = _.groupBy(regionInfos, (regionInfo) -> regionInfo.get("serverHostName"))
      _.each hostNames, (hostName)-> groupedByHost[hostName] = [] unless groupedByHost[hostName]
      values = for own hostName, regionInfos of groupedByHost

        storefileSizeMB = _.reduce(regionInfos, ((sum, regionInfo) -> sum + regionInfo.get('storefileSizeMB')), 0)
        totalReadRate = _.reduce(regionInfos, ((num, ri) -> ri.get('readRate') + num), 0);
        totalWriteRate = _.reduce(regionInfos, ((num, ri) -> ri.get('writeRate') + num), 0);

        x = hostNameMap[hostName]

        switch @plotByField
          when 'storefileSizeMB' then y = storefileSizeMB
          when 'totalReadRate' then y = totalReadRate
          when 'totalWriteRate' then y = totalWriteRate

        {x, y, regionInfos, storefileSizeMB, totalReadRate, totalWriteRate}

      values = _.sortBy(values, (val)-> val.x)
      {} =
        name: tableName
        data: values
        color: @getColor(tableName)

    series.sort( (a, b) -> a.name < b.name )

  createGraphComponents: ->
    components = super()
    graph = components.graph

    hostNameCount = @hostNames.length
    xAxisTicks = hostNameCount

    xAxis = new RickshawUtil.LeftAlignedXAxis
      element: @$('.x-axis').get(0)
      graph: graph,
      ticks: xAxisTicks
      tickFormat: @hostNameAtIndex

    yAxis = new Rickshaw.Graph.Axis.Y
      element: @$('.y-axis').get(0)
      graph: graph
      orientation: 'left'
      tickFormat: (val)=>
        if @plotByField == 'storefileSizeMB'
          if val > 0 then RickshawUtil.humanReadableBytes(val * 1024 * 1024) else 0
        else
          val


    @hoverDetail = new RickshawUtil.InteractiveHoverDetail
      graph: graph
      xFormatter: @hostNameAtIndex
      yFormatter: (y)-> "#{y} MB",
      formatter: (series, x, y, formattedX, formattedY, d) ->
        size = d.value.storefileSizeMB
        count = d.value.regionInfos.length
        totalReadRate = d.value.totalReadRate
        totalWriteRate = d.value.totalWriteRate
        return """
          <b>#{series.name}</b><br/>
          #{size} MB<br/>
          #{count} Regions<br/>
          #{totalReadRate} Read Req. / sec<br />
          #{totalWriteRate} Write Req. / sec
        """
      onClick: (series) =>
        @trigger("table:click", series)

    stackingToggle = new StackingToggleView
      el: @$('.stack-toggle')
      graph: graph

    plotBy = new PlotByView
      el: @$('.plot-by')
      graph: graph
      parentView: @

    allSeriesToggle = new RickshawUtil.AllSeriesToggle
      toggle: components.shelving
      toggleText: "All tables"

    _.extend(components, {xAxis, yAxis, @hoverDetail, stackingToggle, plotBy, allSeriesToggle})

  onTableClick: ->
    @storeLegendState()
    document.location.href = Routes.Tables.show
      name: arguments[0].name