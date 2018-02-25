import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.layout.HBox
import javafx.stage.Stage
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.scene.Cursor
import javafx.scene.ImageCursor
import javafx.scene.canvas.Canvas
import javafx.scene.image.Image
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import org.team2471.frc.lib.vector.Vector2
import javafx.scene.canvas.GraphicsContext
import javafx.scene.control.*
import javafx.scene.text.Text
import org.team2471.frc.lib.motion_profiling.Path2D
import org.team2471.frc.lib.motion_profiling.Path2DPoint
import kotlin.math.round
import javafx.scene.layout.StackPane
import javafx.scene.input.*
import java.text.DecimalFormat
import javafx.stage.FileChooser
import org.team2471.frc.lib.motion_profiling.Autonomi
import org.team2471.frc.lib.motion_profiling.Autonomous
import java.io.File
import java.io.PrintWriter
import java.util.prefs.Preferences

// todo: main application class ////////////////////////////////////////////////////////////////////////////////////////

class PathVisualizer : Application() {

    // todo: the companion object which starts this class //////////////////////////////////////////////////////////////
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(PathVisualizer::class.java, *args)
        }
    }

    // todo: class state - vars and vals ///////////////////////////////////////////////////////////////////////////////
    // javaFX state which needs saved around
    private val fieldCanvas = ResizableCanvas(this)
    private val easeCanvas = ResizableCanvas(this)
    private val image = Image("assets/2018Field.PNG")
    private var stage: Stage? = null
    private val userPref = Preferences.userRoot()
    private val userFilenameKey = "org-frc2471-PathVisualizer-FileName"
    private var fileName = userPref.get(userFilenameKey, "")

    // class state variables

    private var autonomi = Autonomi()
    var selectedAutonomous: Autonomous? = null
    private var selectedPath: Path2D? = null

    // image stuff - measure your image with paint and enter these first 3
    private val upperLeftOfFieldPixels = Vector2(39.0, 58.0)
    private val lowerRightOfFieldPixels = Vector2(624.0, 701.0)
    private val zoomPivot = Vector2(366.0, 701.0)  // the location in the image where the zoom origin will originate
    private val fieldDimensionPixels = lowerRightOfFieldPixels - upperLeftOfFieldPixels
    private val fieldDimensionFeet = Vector2(27.0, 27.0)

    // view settings
    private var zoom: Double = round(feetToPixels(1.0))  // initially draw at 1:1
    var offset: Vector2 = Vector2(0.0, 0.0)

    // location of image extremes in world units
    private val upperLeftFeet = screen2World(Vector2(0.0, 0.0))  // calculate these when zoom is 1:1, and offset is 0,0
    private val lowerRightFeet = screen2World(Vector2(image.width, image.height))

    // drawing
    private val drawCircleSize = 10.0
    private val hitTestCircleSize = 20.0
    private val tangentLengthDrawFactor = 3.0

    // point editing
    private var editPoint: Path2DPoint? = null
    private var selectedPoint: Path2DPoint? = null

    // custom types
    private enum class PointType {
        NONE, POINT, PREV_TANGENT, NEXT_TANGENT
    }

    private var pointType = PointType.NONE

    private enum class MouseMode {
        EDIT, PAN
    }

    private var mouseMode = MouseMode.EDIT

// todo: helper functions //////////////////////////////////////////////////////////////////////////////////////////////

    fun feetToPixels(feet: Double): Double = feet * fieldDimensionPixels.x / fieldDimensionFeet.x

    inline fun <T:Any, R> whenNotNull(input: T?, callback: (T)->R): R? {
        return input?.let(callback)
    }

    private fun world2Screen(vector2: Vector2): Vector2 {
        val temp = vector2 * zoom
        temp.y = -temp.y
        return temp + zoomPivot + offset
    }

    private fun screen2World(vector2: Vector2): Vector2 {
        val temp = vector2 - offset - zoomPivot
        temp.y = -temp.y
        return temp / zoom
    }

    private fun world2ScreenWithMirror(vector2: Vector2, mirror: Boolean): Vector2 {
        val temp = vector2 * zoom
        temp.y = -temp.y
        if (mirror)
            temp.x = -temp.x
        return temp + zoomPivot + offset
    }

    private fun screen2WorldWithMirror(vector2: Vector2, mirror: Boolean): Vector2 {
        val temp = vector2 - offset - zoomPivot
        temp.y = -temp.y
        if (mirror)
            temp.x = -temp.x
        return temp / zoom
    }

    fun Double.format(fracDigits: Int): String {
        val fd = DecimalFormat()
        fd.maximumFractionDigits = fracDigits
        fd.minimumFractionDigits = fracDigits
        return fd.format(this)
    }

// todo: start - this happens when java fx starts our app ///////////////////////////////////////////////////////////////

    override fun start(stage: Stage) {
        stage.title = "Path Visualizer"
        this.stage = stage

        if (!fileName.isEmpty())
            openFile(fileName)
        else {
            // set up the paths and autos
            selectedAutonomous = Autonomous("Auto1")
            selectedAutonomous?.trackWidth = 25.0 / 12
            autonomi.put(selectedAutonomous!!)
            selectedPath = null // Path2D("Path1")  // DefaultPath
            if (selectedPath != null)
                selectedAutonomous!!.putPath(selectedPath!!)
        }

        // setup the layout
        val buttonsBox = VBox()
        buttonsBox.spacing = 10.0
        buttonsBox.padding = Insets(10.0, 10.0, 10.0, 10.0)
        addControlsToButtonsBox(buttonsBox)

        val fieldStackPane = StackPane(fieldCanvas)
        val easeStackPane = StackPane(easeCanvas)
        val verticalSplitPane = SplitPane(fieldStackPane, easeStackPane)
        verticalSplitPane.orientation = Orientation.VERTICAL
        verticalSplitPane.setDividerPositions(0.85)

        val horizontalSplitPane = SplitPane(verticalSplitPane, buttonsBox)
        horizontalSplitPane.setDividerPositions(0.7)

/*
        val menuBar = MenuBar()
        val menuFile = Menu("File")
        menuBar.getMenus().addAll(menuFile);
        val topVBox = VBox()
        topVBox.children.addAll(menuBar, horizontalSplitPane)
*/

        stage.scene = Scene(horizontalSplitPane, 1600.0, 900.0)
        stage.sizeToScene()
        repaint()
        stage.show()

        fieldCanvas.onMousePressed = EventHandler<MouseEvent> { onMousePressed(it) }
        fieldCanvas.onMouseDragged = EventHandler<MouseEvent> { onMouseDragged(it) }
        fieldCanvas.onMouseReleased = EventHandler<MouseEvent> { onMouseReleased() }
        fieldCanvas.onZoom = EventHandler<ZoomEvent> { onZoom(it) }
        fieldCanvas.onKeyPressed = EventHandler<KeyEvent> { onKeyPressed(it) }
        fieldCanvas.onScroll = EventHandler<ScrollEvent> { onScroll(it) }
    }

// todo: stop - this happens when the app shuts down ////////////////////////////////////////////////////////////////////

//    override fun stop() {
//        super.stop()
//    }

// todo: javaFX UI controls //////////////////////////////////////////////////////////////////////////////////////////////////////
    private val autoComboBox = ComboBox<String>()
    private val pathComboBox = ComboBox<String>()
    private val mirroredCheckBox = CheckBox("Mirrored")
    private val robotDirectionBox = ComboBox<String>()
    private val secondsText = TextField()
    private val speedText = TextField()
    private val trackWidthText = TextField()
    private val widthText = TextField()
    private val lengthText = TextField()
    private val scrubFactorText = TextField()

    private fun addControlsToButtonsBox(buttonsBox: VBox) {

        // path combo box
        val pathComboHBox = HBox()
        val pathComboName = Text("Path:  ")
        refreshPathCombo(pathComboBox)
        pathComboBox.valueProperty().addListener({_, _, newText ->
            var newPathName = newText
            if (newPathName=="New Path") {
                var defaultName = "Path"
                var count = 1
                while (selectedAutonomous!!.paths.containsKey(defaultName+count))
                    count++
                val dialog = TextInputDialog(defaultName+count)
                dialog.title = "Path Name"
                dialog.headerText = "Enter the name for your new path"
                dialog.contentText = "Path name:"
                val result = dialog.showAndWait()
                if (result.isPresent) {
                    newPathName = result.get()
                    val newPath = Path2D(newPathName)
                    newPath.addEasePoint(0.0, 0.0); newPath.addEasePoint(5.0,1.0); // always begin with an ease curve
                    selectedAutonomous!!.putPath(newPath)
                    pathComboBox.items.add(pathComboBox.items.count()-1, newPathName)
                }
                else {
                    newPathName = selectedPath?.name
                }
            }
            if (selectedAutonomous!=null) {
                selectedPath = selectedAutonomous!![newPathName]
            }
            pathComboBox.value = newPathName
            selectedPoint = null
            repaint()
        })
        pathComboHBox.children.addAll(pathComboName, pathComboBox)

        // autonomous combo box
        val autoComboHBox = HBox()
        val autoComboName = Text("Auto:  ")
        refreshAutoCombo(autoComboBox)
        autoComboBox.valueProperty().addListener({_, _, newText ->
            var newAutoName = newText
            if (newAutoName=="New Auto") {
                var defaultName = "Auto"
                var count = 1
                while (autonomi.mapAutonomous.containsKey(defaultName+count))
                    count++
                val dialog = TextInputDialog(defaultName+count)
                dialog.title = "Auto Name"
                dialog.headerText = "Enter the name for your new autonomous"
                dialog.contentText = "Auto name:"
                val result = dialog.showAndWait()
                if (result.isPresent) {
                    newAutoName = result.get()
                    val newAuto = Autonomous(newAutoName)
                    autonomi.put(newAuto)
                    autoComboBox.items.add(autoComboBox.items.count()-1, newAutoName)
                }
                else {
                    newAutoName = selectedAutonomous?.name
                }
            }
            selectedAutonomous = autonomi.get(newAutoName)
            autoComboBox.value = newAutoName
            selectedPath = null
            selectedPoint = null
            refreshPathCombo(pathComboBox)
            repaint()
        })
        autoComboHBox.children.addAll(autoComboName, autoComboBox)

        val deletePoint = Button("Delete Point")
        deletePoint.setOnAction { _: ActionEvent ->
            if (selectedPoint != null && selectedPath != null) {
                selectedPath?.removePoint(selectedPoint)
                selectedPoint = null
                repaint()
            }
        }

        mirroredCheckBox.isSelected = if (selectedPath!=null) selectedPath!!.isMirrored else false
        mirroredCheckBox.setOnAction { _: ActionEvent ->
            selectedPath?.isMirrored = mirroredCheckBox.isSelected
            repaint()
        }

        val robotDirectionHBox = HBox()
        val robotDirectionName = Text("Robot Direction:  ")
        robotDirectionBox.items.add("Forward")
        robotDirectionBox.items.add("Backward")
        robotDirectionBox.value = if (selectedPath==null || selectedPath!!.robotDirection==Path2D.RobotDirection.FORWARD) "Forward" else "Backward"
        robotDirectionBox.valueProperty().addListener({ _, _, newText ->
            selectedPath?.robotDirection = if (newText=="Forward") Path2D.RobotDirection.FORWARD else Path2D.RobotDirection.BACKWARD
            repaint()
        })
        robotDirectionHBox.children.addAll(robotDirectionName, robotDirectionBox)

        val secondsHBox = HBox()
        val secondsName = Text("Seconds:  ")
//        val pattern = Pattern.compile("\\d*|\\d+\\,\\d*");
//        val formatter = TextFormatter(UnaryOperator<TextFormatter.Change>) change -> {
//            return pattern.matcher(change.getControlNewText()).matches() ? change : null;
//        })
//        secondsText.setTextFormatter(formatter);
        secondsText.textProperty().addListener({ _, _, newText ->
            selectedPath?.duration = newText.toDouble()
            repaint()
        })
        secondsHBox.children.addAll(secondsName, secondsText)

        val speedHBox = HBox()
        val speedName = Text("Speed Multiplier:  ")
        speedText.textProperty().addListener ({ _, _, newText ->
            val tempSpeed = newText.toDouble()
            selectedPath?.speed = if (tempSpeed!=0.0) tempSpeed else 1.0
            repaint()
        })
        speedHBox.children.addAll(speedName, speedText)

        val trackWidthHBox = HBox()
        val trackWidthName = Text("Track Width:  ")
        //this might perpetually throw an exception at every moment there isn't a path
        // todo: experiment with this and change accordingly
        trackWidthText.textProperty().addListener({ _, _, newText ->
            selectedAutonomous?.trackWidth = (newText.toDouble()) / 12.0
            trackWidthText.text = (selectedAutonomous!!.trackWidth * 12.0).format(1)
            repaint()
        })
        val trackWidthUnit = Text(" inches")
        trackWidthHBox.children.addAll(trackWidthName, trackWidthText, trackWidthUnit)

        val scrubFactorHBox = HBox()
        val scrubFactorName = Text("Width Scrub Factor:  ")
        scrubFactorText.textProperty().addListener({ _, _, newText ->
            selectedAutonomous?.scrubFactor = newText.toDouble()
            repaint()
        })
        scrubFactorHBox.children.addAll(scrubFactorName, scrubFactorText)

        val widthHBox = HBox()
        val widthName = Text("Robot Width:  ")
        //this might perpetually throw an exception at every moment there isn't a path
        // todo: experiment with this and change accordingly
        widthText.textProperty().addListener({ _, _, newText ->
            selectedAutonomous?.robotWidth = (newText.toDouble()) / 12.0
            //widthText.text = (selectedPath!!.robotWidth * 12.0).format(1)
            repaint()
        })
        val widthUnit = Text(" inches")
        widthHBox.children.addAll(widthName, widthText, widthUnit)

        val lengthHBox = HBox()
        val lengthName = Text("Robot Length:  ")
        lengthText.textProperty().addListener({ _, _, newText ->
            selectedAutonomous?.robotLength = newText.toDouble()
            //lengthText.text = (selectedPath!!.robotLength * 12.0).format(1)
            repaint()
        })
        val lengthUnit = Text("inches")
        lengthHBox.children.addAll(lengthName, lengthText, lengthUnit)

        // todo: edit boxes for position and tangents of selected point

        val filesBox = HBox()
        filesBox.spacing = 10.0
        val openButton = Button("Open")
        openButton.setOnAction { _: ActionEvent ->
            val fileChooser = FileChooser()
            fileChooser.setTitle("Open Autonomi File...")
            fileChooser.extensionFilters.add(FileChooser.ExtensionFilter("Autonomi files (*.json)", "*.json"))
            fileChooser.initialDirectory = File(System.getProperty("user.dir"))
            fileChooser.initialFileName = "Test.json"  // this is supposed to be saved in the registry, but it didn't work
            val file = fileChooser.showOpenDialog(stage)
            if (file != null) {
                fileName = file.name
                openFile(file)
            }
        }
        val saveAsButton = Button("Save As")
        saveAsButton.setOnAction { _: ActionEvent ->
            saveAs()
        }
        val saveButton = Button("Save")
        saveButton.setOnAction { _: ActionEvent ->
            if (fileName.isEmpty()) {
                saveAs()
            }
            else {
                val file = File(fileName)
                val json = autonomi.toJsonString()
                val writer = PrintWriter(file)
                writer.append(json)
                writer.close()
            }
        }
        filesBox.children.addAll(openButton, saveAsButton, saveButton)

        val robotHBox = HBox()
        val sendToRobotButton = Button("Send To Robot")
        sendToRobotButton.setOnAction { _: ActionEvent ->
            autonomi.publishToNetworkTables()
        }
        val addressName = Text("  IP Address:  ")
        val addressText = TextField("10.24.71.2")
        addressText.textProperty().addListener({ _, _, newText ->
            autonomi.serverId = newText
        })
        robotHBox.children.addAll(sendToRobotButton, addressName, addressText)

        buttonsBox.children.addAll(
                autoComboHBox,
                pathComboHBox,
                deletePoint,
                Separator(),
                mirroredCheckBox,
                secondsHBox,
                speedHBox,
                robotDirectionHBox,
                Separator(),
                trackWidthHBox,
                scrubFactorHBox,
                widthHBox,
                lengthHBox,
                Separator(),
                filesBox,
                robotHBox
                )

        refreshEverything()
    }

    private fun openFile(fn: String) {
        val file = File(fn)
        openFile(file)
    }

    private fun openFile(file: File) {
        var json: String = file.readText()
        autonomi = Autonomi.fromJsonString(json)
        userPref.put(userFilenameKey, file.name);
        refreshEverything()
    }

    private fun saveAs() {
        val fileChooser = FileChooser()
        fileChooser.setTitle("Save Autonomi File As...")
        val extFilter = FileChooser.ExtensionFilter("Autonomi files (*.json)", "*.json")
        fileChooser.extensionFilters.add(extFilter)
        fileChooser.initialDirectory = File(System.getProperty("user.dir"))
        fileChooser.initialFileName = "Test.json"  // this is supposed to be saved in the registry, but it didn't work
        val file = fileChooser.showSaveDialog(stage)
        if (file != null) {
            fileName = file.name
            val json = autonomi.toJsonString()
            val writer = PrintWriter(file)
            writer.append(json)
            writer.close()
        }
    }

    // todo: UI helper functions //////////////////////////////////////////////////////////////////////////////////////////////////////

    private fun refreshAutoCombo(autoComboBox: ComboBox<String>) {
        autoComboBox.items.clear()
        for (kvAuto in autonomi.mapAutonomous) {
            autoComboBox.items.add(kvAuto.key)
            if (kvAuto.value == selectedAutonomous) {
                autoComboBox.value = kvAuto.key
            }
        }
        autoComboBox.items.add("New Auto")
        if (selectedAutonomous==null) {
            selectedAutonomous = autonomi.mapAutonomous.values.firstOrNull()
            autoComboBox.value = selectedAutonomous?.name
        }
    }

    private fun refreshPathCombo(pathComobBox: ComboBox<String>) {
        pathComobBox.items.clear()
        if (selectedAutonomous!=null) {
            val paths = selectedAutonomous!!.paths
            for (kvPath in paths) {
                pathComobBox.items.add(kvPath.key)
                if (kvPath.value == selectedPath) {
                    pathComobBox.value = kvPath.key
                }
            }
            pathComobBox.items.add("New Path")
            if (selectedPath==null) {
                selectedPath = paths.values.firstOrNull()
                pathComobBox.value = selectedPath?.name
            }
        }
    }

    private fun refreshEverything() {
        refreshAutoCombo(autoComboBox)
        refreshPathCombo(pathComboBox)
        if (selectedPath!=null) {
            mirroredCheckBox.isSelected = selectedPath!!.isMirrored
            robotDirectionBox.value = if (selectedPath!!.robotDirection == Path2D.RobotDirection.FORWARD) "Forward" else "Backward"
            secondsText.text = selectedPath!!.duration.format(1)
            speedText.text = selectedPath!!.speed.format(1)
        }
        if (selectedAutonomous!=null) {
            trackWidthText.text = (selectedAutonomous!!.trackWidth * 12.0).format(1)
            widthText.text = (selectedAutonomous!!.robotWidth * 12.0).format(1)
            lengthText.text = (selectedAutonomous!!.robotLength * 12.0).format(1)
            scrubFactorText.text = selectedAutonomous!!.scrubFactor.format(3)
        }
    }

// todo: draw functions ////////////////////////////////////////////////////////////////////////////////////////////////

    fun repaint() {
        val gc = fieldCanvas.graphicsContext2D
        gc.fill = Color.LIGHTGRAY
        gc.fillRect(0.0, 0.0, fieldCanvas.width, fieldCanvas.height)

        // calculate ImageView corners
        val upperLeftPixels = world2Screen(upperLeftFeet)
        val lowerRightPixels = world2Screen(lowerRightFeet)
        val dimensions = lowerRightPixels - upperLeftPixels
        gc.drawImage(image, 0.0, 0.0, image.width, image.height, upperLeftPixels.x, upperLeftPixels.y, dimensions.x, dimensions.y)

        drawPaths(gc)
    }

    private fun drawPaths(gc: GraphicsContext) {
        if (selectedAutonomous != null) {
            for (path2D in selectedAutonomous!!.paths) {
                drawPath(gc, path2D.value)
            }
        }
        drawSelectedPath(gc, selectedPath)
    }

    private fun drawPath(gc: GraphicsContext, path2D: Path2D?) {
        if (path2D == null || path2D.duration==0.0)
            return
        path2D.resetDistances()
        val deltaT = path2D.durationWithSpeed / 200.0
        val prevPos = path2D.getPosition(0.0)
        var pos: Vector2

        gc.stroke = Color.WHITE
        var t = deltaT
        while (t <= path2D.durationWithSpeed) {
            pos = path2D.getPosition(t)

            // center line
            drawPathLine(gc, prevPos, pos)
            prevPos.set(pos.x, pos.y)
            t += deltaT
        }
    }

    private fun drawPathLine(gc: GraphicsContext, p1: Vector2, p2: Vector2) {
        val tp1 = world2Screen(p1)
        val tp2 = world2Screen(p2)
        gc.strokeLine(tp1.x, tp1.y, tp2.x, tp2.y)
    }

    private fun drawSelectedPath(gc: GraphicsContext, path2D: Path2D?) {
        if (path2D == null || !path2D.hasPoints())
            return
        if (path2D.durationWithSpeed > 0.0) {
            val deltaT = path2D.durationWithSpeed / 200.0
            var prevLeftPos = path2D.getLeftPosition(0.0)
            var prevRightPos = path2D.getRightPosition(0.0)
            var pos: Vector2
            var leftPos: Vector2
            var rightPos: Vector2
            val MAX_SPEED = 8.0
            var t = deltaT
            path2D.resetDistances()
            while (t <= path2D.durationWithSpeed) {
                val ease = t/path2D.durationWithSpeed
                leftPos = path2D.getLeftPosition(t)
                rightPos = path2D.getRightPosition(t)

                // left wheel
                var leftSpeed = Vector2.length(Vector2.subtract(leftPos, prevLeftPos)) / deltaT
                leftSpeed /= MAX_SPEED  // MAX_SPEED is full GREEN, 0 is full red.
                leftSpeed = Math.min(1.0, leftSpeed)
                val leftDelta = path2D.getLeftPositionDelta(t)
                if (leftDelta >= 0) {
                    gc.stroke = Color(1.0 - leftSpeed, leftSpeed, 0.0, 1.0)
                    //gc.stroke = Color(ease*Color.YELLOW.red, ease*Color.YELLOW.green, ease*Color.YELLOW.blue, 1.0)
                }
                else {
                    gc.stroke = Color.BLUE
                    //gc.stroke = Color(ease*Color.LIMEGREEN.red, ease*Color.LIMEGREEN.green, ease*Color.LIMEGREEN.blue, 1.0)
                }
                drawPathLine(gc, prevLeftPos, leftPos)

                // right wheel
                var rightSpeed = Vector2.length(Vector2.subtract(rightPos, prevRightPos)) / deltaT / MAX_SPEED
                rightSpeed = Math.min(1.0, rightSpeed)
                val rightDelta = path2D.getRightPositionDelta(t)
                if (rightDelta >= 0) {
                    gc.stroke = Color(1.0 - rightSpeed, rightSpeed, 0.0, 1.0)
                    //gc.stroke = Color(ease * Color.RED.red, ease * Color.RED.green, ease * Color.RED.blue, 1.0)
                }
                else {
                    gc.stroke = Color.BLUE
                    //gc.stroke = Color(ease*Color.BLUE.red, ease*Color.BLUE.green, ease*Color.BLUE.blue, 1.0)
                }
                drawPathLine(gc, prevRightPos, rightPos)

                // set the prevs for the next loop
                prevLeftPos = leftPos.copy()
                prevRightPos = rightPos.copy()
                t += deltaT
            }
        }

        // circles and lines for handles
        var point: Path2DPoint? = path2D.xyCurve.headPoint
        while (point != null) {
            if (point === selectedPoint && pointType == PointType.POINT)
                gc.stroke = Color.LIMEGREEN
            else
                gc.stroke = Color.WHITE

            val tPoint = world2ScreenWithMirror(point.position, path2D.isMirrored)
            gc.strokeOval(tPoint.x - drawCircleSize / 2, tPoint.y - drawCircleSize / 2, drawCircleSize, drawCircleSize)
            if (point.prevPoint != null) {
                if (point === selectedPoint && pointType == PointType.PREV_TANGENT)
                    gc.stroke = Color.LIMEGREEN
                else
                    gc.stroke = Color.WHITE
                val tanPoint = world2ScreenWithMirror(Vector2.subtract(point.position, Vector2.multiply(point.prevTangent, 1.0 / tangentLengthDrawFactor)), path2D.isMirrored)
                gc.strokeOval(tanPoint.x - drawCircleSize / 2, tanPoint.y - drawCircleSize / 2, drawCircleSize, drawCircleSize)
                gc.lineWidth = 2.0
                gc.strokeLine(tPoint.x, tPoint.y, tanPoint.x, tanPoint.y)
            }
            if (point.nextPoint != null) {
                if (point === selectedPoint && pointType == PointType.NEXT_TANGENT)
                    gc.stroke = Color.LIMEGREEN
                else
                    gc.stroke = Color.WHITE
                val tanPoint = world2ScreenWithMirror(Vector2.add(point.position, Vector2.multiply(point.nextTangent, 1.0 / tangentLengthDrawFactor)), path2D.isMirrored)
                gc.strokeOval(tanPoint.x - drawCircleSize / 2, tanPoint.y - drawCircleSize / 2, drawCircleSize, drawCircleSize)
                gc.lineWidth = 2.0
                gc.strokeLine(tPoint.x, tPoint.y, tanPoint.x, tanPoint.y)
            }
            point = point.nextPoint
        }
    }

    fun drawEaseCurve() {
        // draw the ease curve  // be nice to draw this beneath the map
        //    double prevEase = 0.0;
        //    gc.setStroke(new BasicStroke(3));
        //    for (double t = deltaT; t <= path2D.getDuration(); t += deltaT) {
        //      // draw the ease curve too
        //      gc.setColor(Color.black);
        //      double ease = path2D.getEaseCurve().getValue(t);
        //      double prevT = t - deltaT;
        //      gc.drawLine((int) (prevT * 40 + 100), (int) (prevEase * -200 + 700), (int) (t * 40 + 100), (int) (ease * -200 + 700));
        //      prevEase = ease;
        //    }
    }

    var startMouse = Vector2(0.0, 0.0)

// todo: mouse functions ///////////////////////////////////////////////////////////////////////////////////////////////
    var oCoord: Vector2 = Vector2(0.0, 0.0)
    fun onMousePressed(e: MouseEvent) {
        if (e.isMiddleButtonDown || e.isSecondaryButtonDown) {
            fieldCanvas.cursor = Cursor.CROSSHAIR
            mouseMode = MouseMode.PAN
        }
        when (mouseMode) {
            MouseMode.EDIT -> {
                val mouseVec = Vector2(e.x, e.y)
                startMouse = mouseVec

                var shortestDistance = 10000.0
                var closestPoint: Path2DPoint? = null

                //Find closest point
                var point: Path2DPoint? = selectedPath?.xyCurve?.headPoint
                while (point != null) {
                    val tPoint = world2ScreenWithMirror(point.position, selectedPath!!.isMirrored)
                    var dist = Vector2.length(Vector2.subtract(tPoint, mouseVec))
                    if (dist <= shortestDistance) {
                        shortestDistance = dist
                        closestPoint = point
                        pointType = PointType.POINT
                    }

                    if (point.prevPoint != null) {
                        val tanPoint1 = world2ScreenWithMirror(Vector2.subtract(point.position, Vector2.multiply(point.prevTangent, 1.0 / tangentLengthDrawFactor)), selectedPath!!.isMirrored)
                        dist = Vector2.length(Vector2.subtract(tanPoint1, mouseVec))
                        if (dist <= shortestDistance) {
                            shortestDistance = dist
                            closestPoint = point
                            pointType = PointType.PREV_TANGENT
                        }
                    }

                    if (point.nextPoint != null) {
                        val tanPoint2 = world2ScreenWithMirror(Vector2.add(point.position, Vector2.multiply(point.nextTangent, 1.0 / tangentLengthDrawFactor)), selectedPath!!.isMirrored)
                        dist = Vector2.length(Vector2.subtract(tanPoint2, mouseVec))
                        if (dist <= shortestDistance) {
                            shortestDistance = dist
                            closestPoint = point
                            pointType = PointType.NEXT_TANGENT
                        }
                    }
                    point = point.nextPoint
                    // find distance between point clicked and each point in the graph. Whichever one is the max gets to be assigned to the var.
                }
                if (shortestDistance <= hitTestCircleSize / 2) {
                    selectedPoint = closestPoint
                } else {
                    if (closestPoint != null) {
                        if (shortestDistance > 50) // trying to deselect?
                            selectedPoint = null
                        else
                            selectedPoint = selectedPath?.addVector2After(screen2World(mouseVec), closestPoint)
                    } else {  // first point on a path?
                        //                val path2DPoint = selectedPath?.addVector2(screen2World(mouseVec)-Vector2(0.0,0.25)) // add a pair of points, initially on top of one another
                        //                selectedPoint = selectedPath?.addVector2After(screen2World(mouseVec), path2DPoint)
                        selectedPath?.addVector2(screen2World(mouseVec))
                    }
                }
                editPoint = selectedPoint
                repaint()
            }
            MouseMode.PAN -> {
                fieldCanvas.cursor = ImageCursor.CROSSHAIR
                oCoord = Vector2(e.x, e.y) - offset
            }
        }
    }

    fun onMouseDragged(e: MouseEvent) {
        when (mouseMode) {
            MouseMode.EDIT -> {
                if (editPoint != null) {
                    val worldPoint = screen2WorldWithMirror(Vector2(e.x, e.y), selectedPath!!.isMirrored)
                    when (pointType) {
                        PathVisualizer.PointType.POINT -> editPoint?.position = worldPoint
                        PathVisualizer.PointType.PREV_TANGENT -> editPoint!!.prevTangent = Vector2.multiply(Vector2.subtract(worldPoint, editPoint!!.position), -tangentLengthDrawFactor)
                        PathVisualizer.PointType.NEXT_TANGENT -> editPoint!!.nextTangent = Vector2.multiply(Vector2.subtract(worldPoint, editPoint!!.position), tangentLengthDrawFactor)
                        else -> {
                        }
                    }
                    repaint()
                }
            }
            MouseMode.PAN -> {
                //println("${offset.x} and ${offset.y}")
                offset.x = e.x - oCoord.x
                offset.y = e.y - oCoord.y
                repaint()
            }
        }
    }

    fun onMouseReleased() {
        when (mouseMode) {
           MouseMode.EDIT -> editPoint = null  // no longer editing
           MouseMode.PAN -> mouseMode = MouseMode.EDIT
        }
        fieldCanvas.cursor = Cursor.DEFAULT
        fieldCanvas.requestFocus()
    }


    fun onZoom(e: ZoomEvent) {
        zoom *= e.zoomFactor
        repaint()
    }


    fun onKeyPressed(e: KeyEvent) {
        if (e.isControlDown) {
            when (e.text) {
                "=" -> {
                    zoom++

                }
                "-" -> {
                    zoom--
                }
            }
            //zoomAdjust.text = zoom.toString()
        }
        when (e.text) {
            "p" -> {
                fieldCanvas.cursor = ImageCursor.CROSSHAIR
                mouseMode = MouseMode.PAN
            }
        }
        repaint()
    }

    fun onScroll(e: ScrollEvent) {
        if (mouseMode != MouseMode.PAN) {
            zoom -= e.deltaY / 25
            repaint()
        }
    }

}

// todo: resizable canvas //////////////////////////////////////////////////////////////////////////////////////////////

class ResizableCanvas(pv: PathVisualizer) : Canvas() {

    private var pathVisualizer = pv

    override fun isResizable() = true

    override fun prefWidth(height: Double) = width

    override fun prefHeight(width: Double) = height

    override fun minHeight(width: Double): Double {
        return 64.0
    }

    override fun maxHeight(width: Double): Double {
        return 1000.0
    }

    override fun minWidth(height: Double): Double {
        return 0.0
    }

    override fun maxWidth(height: Double): Double {
        return 10000.0
    }

    override fun resize(_width: Double, _height: Double) {
        width = _width
        height = _height
        pathVisualizer.repaint()
    }
}

// todo list  //////////////////////////////////////////////////////////////////////////////////////////////////////

// : mouse routines - down, move, up
// : edit boxes respond - zoom, and pan
// : investigate why mirrored is not working
// : try layoutpanel for making buttons follow size of window on right - used splitpane and resizable
// : get path combo working
// : handle cancel on new dialogs
// : generate unique name for Auto and Path
// : new path draws blank
// : get autonomous combo working
// : delete point button
// : add path properties - ...
// : mirrored,
// : speed,
// : travel direction,
// : robot width, length, fudgefactor
// : change the displayed value of robot width and length to inch (it is currently in feet)
// : mirrored,
// : speed,
// : travel direction,
// : robot width, length, fudgefactor
// : convert robot width and length to inches - Duy
// : save to file, load from file
// : save to network tables for pathvisualizer
// : load from network tables for robot
// : add text box for team number or ip
// : pan with mouse with a pan button or middle mouse button -- Julian
// : zoom with the mouse wheel -- Julian
// : make a separate and larger radius for selecting points compared to drawing them
// : edit box for duration of path,
// : support mirror state of the path (where?  in getPosition() or in worldToScreen()?)
// : fix robotDirection and speed properties
// : rename robotWidth in path to trackWidth, add robotLength and robotWidth to Autonomous for drawing
// : set initial folder to the output folder for open and save

// todo: -Bob-
// todo: draw ease curve in bottom panel, use another SplitPane horizontal
// todo: place path duration in bottom corner of ease canvas using StackPane
// todo: write autonomous or path to the network tables as a single json key/value pair instead of autonomi root

// todo: invert pinch zooming - Julian

// todo: remember last loaded/saved file in registry and automatically load it at startup

// todo: add edit boxes for x, y coordinate of selected point and magnitude and angle of tangent points
// todo: add rename button beside auto and path combos to edit their names -- Duy
// todo: add delete buttons beside auto and path for deleting them
// todo: change path combo to a list box
// todo: add edit box for what speed is colored maximum green
// todo: upres or repaint a new high res field image
// todo: clicking on path should select it
// todo: arrow keys to nudge selected path points

// todo: playback of robot travel - this should be broken into sub tasks
// todo: add partner1 and partner2 auto combos - draw cyan, magenta, yellow
// todo: editing of ease curve
// todo: multi-select path points by dragging selecting with dashed rectangle
// todo: add pause and turn in place path types (actions)
// todo: decide what properties should be saved locally and save them to the registry or local folder
// todo:
