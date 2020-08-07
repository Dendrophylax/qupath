/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.gui.viewer.recording;

import java.awt.Dimension;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.EventHandler;
import javafx.scene.input.MouseEvent;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;


/**
 * Default ViewTracker implementation.
 * <p>
 * This tracks only viewer location and cursor position (no eye tracking... because it can't see you). 
 * It does <i>not</i> handle viewer rotations.
 * <p>
 * TODO: Deal with rotations in the tracker.
 * 
 * @author Pete Bankhead
 *
 */
public class DefaultViewTracker implements ViewTracker, QuPathViewerListener {
	// TODO: Somehow it doesn't always reset the frame array?
	// TODO: Set a max recording time limit?
	
	private final static Logger logger = LoggerFactory.getLogger(DefaultViewTracker.class);

	static DecimalFormat df = new DecimalFormat("#.##");
	protected static final String LOG_DELIMITER = "\t";

	transient private QuPathGUI qupath;
	transient private QuPathViewer viewer;
	
	private boolean hasZAndT;
	
	private static BooleanProperty trackCursorPosition = PathPrefs.createPersistentPreference("trackCursorPosition", true);

	private BooleanProperty recording = new SimpleBooleanProperty(false);

	private File recordingDirectory;
	private File recordingFile = null;
	private OutputStreamWriter fw = null;
	private String name = null;
	
	private boolean doCursorTracking = true;

	private long startTime = -1;

	private List<ViewRecordingFrame> frames = new ArrayList<>();
	transient ViewRecordingFrame lastFrame = null;
	private boolean hasEyeTrackingData = false;
	private double rotation = 0;
	
	private boolean initialized = false;
	
	private MouseMovementHandler mouseHandler = new MouseMovementHandler();


	DefaultViewTracker(final QuPathGUI qupath) {
		this.qupath = qupath;
		viewer = qupath != null ? qupath.getViewer() : null;
		recording.addListener((v, o, n) -> {
			if (n)
				doStartRecording();
			else
				doStopRecording();
		});
	}
	
	@Override
	public int nFrames() {
		return frames.size();
	}

	@Override
	public ViewRecordingFrame getFrame(int index) {
		return frames.get(index);
	}


	@Override
	public void setRecording(final boolean recording) {
		if (isRecording() == recording)
			return;
		this.recording.set(recording);
	}
	
	
	private void doStartRecording() {
		// Check we aren't recording already
		if (viewer == null || viewer.getServer() == null || initialized)
			return;
		
		// Look for a server
		ImageServer<BufferedImage> server = viewer.getServer();
		initializeRecording();
		viewer.addViewerListener(this);
		doCursorTracking = trackCursorPosition.get();
		if (doCursorTracking) {
			viewer.getView().addEventHandler(MouseEvent.MOUSE_MOVED, mouseHandler);
			viewer.getView().addEventHandler(MouseEvent.MOUSE_DRAGGED, mouseHandler);
		}
		// Assume no eye tracking data until we learn otherwise
		hasEyeTrackingData = false;
		visibleRegionChanged(viewer, viewer.getDisplayedRegionShape());

		logger.debug("--------------------------------------\n" + 
					"View tracking for image: " + server.getPath() + "\n" +
					ViewTrackers.getSummaryHeadings(LOG_DELIMITER, doCursorTracking, supportsEyeTracking(), hasZAndT()));
	}



	private boolean setRecordingDirectory() {
		Path entryPath = qupath.getProject().getEntry(viewer.getImageData()).getEntryPath();
		if (entryPath != null && entryPath.toFile().exists()) {
			File recordingDirectory = new File(entryPath.toFile(), "recordings");
			if (recordingDirectory.exists()) {
				this.recordingDirectory = recordingDirectory;
				return true;
			}
		}
		return false;
	}
	

	private void createRecordingDir(Path entryPath) {
		if (entryPath == null) {
			logger.warn("Could not set recording directory.");
			return;			
		}
		File directory = new File(entryPath.toFile(), "recordings");
		directory.mkdir();
		
		recordingDirectory = directory;
	}

	@Override
	public boolean isRecording() {
		return recording.get();
	}


	protected QuPathViewer getViewer() {
		return viewer;
	}


	private void doStopRecording() {
		logger.debug("--------------------------------------");
		viewer.removeViewerListener(this);
		if (doCursorTracking) {
			viewer.getView().removeEventHandler(MouseEvent.MOUSE_MOVED, mouseHandler);
			viewer.getView().removeEventHandler(MouseEvent.MOUSE_DRAGGED, mouseHandler);
		}
		if (fw != null) {
			try {
				fw.flush();
				fw.close();
				fw = null;
			} catch (IOException e) {
				logger.error("Error while closing back-up file: ", e);
			}
					
		}
	}


	@Override
	public void resetRecording() {
		if (isRecording())
			setRecording(false);
		frames.clear();
		lastFrame = null;
//		initializeRecording(null, 0, 0);
	}

	private void initializeRecording() {
		var imageData = viewer.getImageData();
		hasZAndT = imageData.getServer().getMetadata().getSizeZ() != 1 || viewer.getImageData().getServer().getMetadata().getSizeT() != 1;
		frames.clear();
		startTime = System.currentTimeMillis();
		lastFrame = null;
		
		// Create 'recordings' directory if it doesn't exist
		if (!setRecordingDirectory())
			createRecordingDir(qupath.getProject().getEntry(viewer.getImageData()).getEntryPath());
		
		recordingFile = recordingFile != null ? recordingFile : new File(recordingDirectory, name + ".tsv");
		try {
			fw = new OutputStreamWriter(new FileOutputStream(recordingFile), StandardCharsets.UTF_8);
			fw.write(ViewTrackers.getSummaryHeadings(LOG_DELIMITER, doCursorTracking, supportsEyeTracking(), hasZAndT()));
			fw.write(System.lineSeparator());
		} catch (IOException e) {
			logger.error("Could not create back-up file. Recording will not be saved.", e.getLocalizedMessage());
		}
		
		initialized = true;
	}

	//	public boolean isEmpty() {
	//		return !initialized || frames.isEmpty();
	//	}

	@Override
	public boolean isEmpty() {
		return frames.isEmpty();
	}


	protected boolean supportsEyeTracking() {
		return false;
	}

	/**
	 * 
	 * @param timestamp
	 * @param imageBounds
	 * @param canvasSize
	 * @param downFactor
	 * @param cursorPoint
	 * @param eyePoint
	 * @param isFixated 
	 * @param z 
	 * @param t 
	 * @return The frame, if one was added, or null otherwise.
	 */
	protected synchronized ViewRecordingFrame addFrame(final long timestamp, final Shape imageBounds, final Dimension canvasSize, final double downFactor, final Point2D cursorPoint, final Point2D eyePoint, final Boolean isFixated, double rotation, int z, int t) {
		if (!isRecording()) {
			logger.error("Recording has not started!  Frame request will be ignored.");
		}

		if (lastFrame != null && lastFrame.getTimestamp() > timestamp) { // Shouldn't happen... but disregard out-of-order processing
			logger.warn("View tracking frame disregarded with timestamp " + df.format((timestamp - startTime)/1000) + " seconds");
			return null;
		}
	
		DefaultViewRecordingFrame frame = new DefaultViewRecordingFrame(timestamp-startTime, imageBounds, canvasSize, downFactor, rotation, cursorPoint, eyePoint, isFixated, z, t);
		appendFrame(frame);
		// Log the frame
		logger.debug(ViewTrackers.getSummary(lastFrame, LOG_DELIMITER, doCursorTracking, hasEyeTrackingData, hasZAndT));
		return frame;
	}


	@Override
	public synchronized void appendFrame(final ViewRecordingFrame frame) {
		if (lastFrame != null && lastFrame.getTimestamp() > frame.getTimestamp())
			throw new RuntimeException("Unable to append frame - frame timestamp is earlier than the current timestamp");
		frames.add(frame);
		lastFrame = frame;
		hasEyeTrackingData = hasEyeTrackingData || frame.hasEyePosition();
		
		if (fw != null) {
			try {
				fw.write(ViewTrackers.getSummary(frame, "\t", doCursorTracking, hasEyeTrackingData, hasZAndT));
				fw.write(System.lineSeparator());
			} catch (IOException e) {
				logger.error("Could not write frame to file. Frame will be ignored: ", e);
			}			
		}
	}



	@Override
	public boolean hasEyeTrackingData() {
		return hasEyeTrackingData;
	}
	
	private int getCurrentZ() {
		return viewer.getZPosition();
	}
	
	private int getCurrentT() {
		return viewer.getTPosition();
	}

	
	/**
	 * Return an unmodifiable list of all the frames stored by this view tracker.
	 * @return
	 */
	public List<ViewRecordingFrame> getFrames() {
		return Collections.unmodifiableList(frames);
	}

	@Override
	public boolean isLastFrame(ViewRecordingFrame frame) {
		return frame == lastFrame;
	}
	
	/**
	 * Returns the index of frame visible at time t (in ms). If no 
	 * frame was visible at the time yet (e.g. first recorded frame is 
	 * after t), it returns 0.
	 * 
	 * @param t
	 * @return
	 */
	@Override
	public int getFrameIndexForTime(long t) {
		ArrayList<Long> timestampList = frames.stream().map(e -> e.getTimestamp()).collect(Collectors.toCollection(ArrayList::new));
	    int index = Collections.binarySearch(timestampList, t);
	    if (index < 0)
	        index = ~index-1;
	    return index >= 0 ? index : 0;
	}
	
	/**
	 * Returns the frame visible at time t (in ms)
	 * 
	 * @param t
	 * @return
	 */
	@Override
	public ViewRecordingFrame getFrameForTime(long t) {
		int index = getFrameIndexForTime(t);
	    return frames.get(index);
	}
	

	@Override
	public void visibleRegionChanged(final QuPathViewer viewer, final Shape shape) {
		// If the image has been updated, then it could be because a change of view that we want to track
		if (lastFrame != null && lastFrame.getImageShape().equals(shape) && lastFrame.getSize().equals(getSize(viewer)))
			return;
		
		rotation = viewer.getRotation() != rotation ? viewer.getRotation() : rotation;

		addFrame(System.currentTimeMillis(), shape, getSize(viewer), viewer.getDownsampleFactor(), getMousePointIfRequired(), null, null, rotation, getCurrentZ(), getCurrentT());
	}

	static Dimension getSize(QuPathViewer viewer) {
		return new Dimension((int)Math.round(viewer.getView().getWidth()), (int)Math.round(viewer.getView().getHeight()));
	}

	protected Point2D getMousePointIfRequired() {
		// Get the mouse position, if required
		Point2D p = null;
		if (doCursorTracking) {
			//			p = MouseInfo.getPointerInfo().getLocation(); // We don't want cursor positions outside the component, because they may be confusing when converted to image coordinates
			p = viewer.getMousePosition();
			if (p != null)
				return viewer.componentPointToImagePoint(p, p, false);
		}
		return null;
	}


	@Override
	public void selectedObjectChanged(QuPathViewer viewer, PathObject pathObjectSelected) {}


	@Override
	public void viewerClosed(QuPathViewer viewer) {
		if (this.viewer == viewer)
			setRecording(false);
	}


	@Override
	public BooleanProperty recordingProperty() {
		return recording;
	}
	
	
	
	class MouseMovementHandler implements EventHandler<MouseEvent> {

		@Override
		public void handle(MouseEvent event) {
			Point2D p = viewer.componentPointToImagePoint(event.getX(), event.getY(), null, false);
			addFrame(System.currentTimeMillis(), viewer.getDisplayedRegionShape(), getSize(viewer), viewer.getDownsampleFactor(), p, null, null, viewer.getRotation(), getCurrentZ(), getCurrentT());
		}		
		
	}


	@Override
	public void imageDataChanged(QuPathViewer viewer, ImageData<BufferedImage> imageDataOld,
			ImageData<BufferedImage> imageDataNew) {}

	

	@Override
	public File getFile() {
		return recordingFile;
	}
	
	@Override
	public void setFile(File file) {
		this.recordingFile = file;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void setName(String name) {
		this.name = name;
	}
	
	@Override
	public long getStartTime() {
		if (startTime == -1 && frames.size() > 0)
			return frames.get(0).getTimestamp();
		return startTime;
	}
	
	@Override
	public long getLastTime() {
		return lastFrame.getTimestamp() + getStartTime();
	}

	@Override
	public boolean hasZAndT() {
		return hasZAndT;
	}
	
	@Override
	public boolean hasCursorTrackingData() {
		return doCursorTracking;
	}

	@Override
	public List<ViewRecordingFrame> getAllFrames() {
		return frames;
	}
}