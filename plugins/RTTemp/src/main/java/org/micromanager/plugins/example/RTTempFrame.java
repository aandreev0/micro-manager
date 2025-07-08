/*
 * RTTempFrame.java
 * <p>
 * Based on ExampleFrame, shows RealTime intensity plots during live views or acquisitions.
 * <p>
 * Nico Stuurman, Carlos Mendioroz copyright UCSF, 2020
 * <p>
 * LICENSE: This file is distributed under the BSD license. License text is
 * included with the source distribution.
 * <p>
 * This file is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE.
 * <p>
 * IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package org.micromanager.plugins.rttemp;

import com.google.common.eventbus.Subscribe;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import net.miginfocom.swing.MigLayout;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartFrame;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import mmcorej.CMMCore;

import org.micromanager.Studio;
import org.micromanager.acquisition.AcquisitionStartedEvent;
import org.micromanager.data.DataProvider;
import org.micromanager.data.DataProviderHasNewImageEvent;
import org.micromanager.data.Image;
import org.micromanager.display.DataViewer;
import org.micromanager.events.LiveModeEvent;
import org.micromanager.internal.utils.WindowPositioning;
import org.micromanager.propertymap.MutablePropertyMapView;

public class RTTempFrame extends JFrame {
   private static final int MAX_ROIS = 100;
   private final Studio studio_;
   private CMMCore core_;
   // A reference to the event handling (only?) instance
   private static Object RThandler_ = null;
   private DataProvider dataProvider_;
   // Only one chart for the time being
   private ChartFrame graphFrame_ = null;
   private double lastElapsedTimeMs_ = 0.0;
   // autoStart on new active window
   private boolean autoStart_;
   // acquisition plot start on first image delivered.
   private boolean delayedStart_ = false;
   // Ratio plot memory
   private boolean ratio_;
   private final double[] last_ = new double[MAX_ROIS];
   // This is our local cache ...
   private int temps_ = 3;
   private final Roi[] roi_ = new Roi[MAX_ROIS];
   // of the manager kept ROIs
   private RoiManager manager_;
   private int imagesReceived_ = 0;
   private static final long serialVersionUID = 1;
   // How many channels does the image have ? 
   private int channels_ = 0;
   // how many series do we plot ?
   private int plots_ = 0;
   // how many extra data do we need for a time point
   private int missing_ = 0;
   private final JLabel title_;
   XYSeries[] data_ = null;
   // Doing background "equalization" ?
   private int backgroundEq_ = -1;
   // Min refresh time (ms)
   private int minPeriod_;
   // Max plot points
   private int maxPoints_;
   private String[] sensor_id_ = {"InternalTemp", "Th1", "Th2"};

   private static final String MIN_PERIOD = "min_period";
   private static final String MAX_POINTS = "max_points";
   private static final String RATIO = "ratio";
   private static final String AUTOSTART = "autostart";

   public RTTempFrame(Studio studio) {
      super("Real time intensity GUI");
      studio_ = studio;
      core_ = studio.getCMMCore();
      final MutablePropertyMapView prefs = studio.profile().getSettings(this.getClass());
      minPeriod_ = prefs.getInteger(MIN_PERIOD, 10);
      maxPoints_ = prefs.getInteger(MAX_POINTS, 200);
      ratio_ = prefs.getBoolean(RATIO, false);
      autoStart_ = prefs.getBoolean(AUTOSTART, false);

      if (RThandler_ == null) {
         RThandler_ = this;
      }
      super.setLocation(100, 100); // Default location
      WindowPositioning.setUpLocationMemory(this, RTTempFrame.class, "Main");
      super.setIconImage(Toolkit.getDefaultToolkit().getImage(
            getClass().getResource("/org/micromanager/icons/microscope.gif")));

      super.setLayout(new MigLayout("fill, insets 2, gap 2, flowx"));

      // Tell user what to do, in case he does not know...
      title_ = new JLabel("                Prepare for instructions!            ");
      title_.setFont(new Font("Arial", Font.BOLD, 14));
      super.add(title_, "span, alignx center, wrap");

      // Shortcut to ROI Manager
      JButton managerButton = new JButton("ROI Manager");
      managerButton.addActionListener(e -> {
         if (manager_ == null) {
            manager_ = new RoiManager();
         }
         manager_.setVisible(true);
      });
      //super.add(managerButton, "split, span");

      // Create a graph and start plotting, or tell user what's missing for so doing
      JButton startButton = new JButton("Plot");
      startButton.addActionListener(e -> {
         // Active window ?
         DataViewer viewer = studio.displays().getActiveDataViewer();
         if (viewer == null) {
            title_.setText("You need an open window (Snap/Live).");
            return;
         }
         dataProvider_ = viewer.getDataProvider();
         channels_ = dataProvider_.getNextIndex("channel");
         
         // We are all set ?
         setupPlot();
      });
      startButton.setEnabled(true);
      super.add(startButton, "span");

      // Define custom settings
      JButton settingsButton = new JButton("Settings");
      settingsButton.addActionListener(e -> {
         JTextField period = new JTextField(5);
         period.setText(Integer.toString(minPeriod_));
         JTextField maxPoints = new JTextField(5);
         maxPoints.setText(Integer.toString(maxPoints_));
         JCheckBox ratioPlot = new JCheckBox();
         ratioPlot.setSelected(ratio_);
         JCheckBox autoPlot = new JCheckBox();
         autoPlot.setSelected(autoStart_);

         JPanel settingsPanel = new JPanel();
         settingsPanel.setLayout(new MigLayout("fill, insets 2, gap 2, flowx"));

         settingsPanel.add(new JLabel("min refresh period(ms):"));
         settingsPanel.add(period, "wrap");

         settingsPanel.add(new JLabel("max data points:"));
         settingsPanel.add(maxPoints, "wrap");

         settingsPanel.add(new JLabel("2 channel ratio (1/2) plot:"));
         settingsPanel.add(ratioPlot, "wrap");

         settingsPanel.add(new JLabel("autostart plot:"));
         settingsPanel.add(autoPlot, "wrap");

         int result = JOptionPane.showConfirmDialog(this, settingsPanel,
               "Plot settings", JOptionPane.OK_CANCEL_OPTION);
         if (result == JOptionPane.OK_OPTION) {
            minPeriod_ = new Integer(period.getText());
            prefs.putInteger(MIN_PERIOD, minPeriod_);
            maxPoints_ = new Integer(maxPoints.getText());
            prefs.putInteger(MAX_POINTS, maxPoints_);
            ratio_ = ratioPlot.isSelected();
            prefs.putBoolean(RATIO, ratio_);
            autoStart_ = autoPlot.isSelected();
            prefs.putBoolean(AUTOSTART, autoStart_);
         }
      });
      settingsButton.setEnabled(true);
      //super.add(settingsButton, "wrap");

      super.pack();

      // Registering this class for events means that its event handlers
      // (that is, methods with the @Subscribe annotation) will be invoked when
      // an event occurs. You need to call the right registerForEvents() method
      // to get events; this one is for the application-wide event bus, but
      // there's also Datastore.registerForEvents() for events specific to one
      // Datastore, and DisplayWindow.registerForEvents() for events specific
      // to one image display window.
      studio_.events().registerForEvents(this);
      title_.setText("You need an active window and a ROI ...");
   }

   @Subscribe
   public void onNewAcquisition(AcquisitionStartedEvent event) {
      if (!autoStart_ || manager_ == null) {
         return;
      }
     
      title_.setText("Set");
      delayedStart_ = true;
      event.getDatastore().registerForEvents(RThandler_);
   }

   @Subscribe
   public void onLiveMode(LiveModeEvent event) {
      if (!autoStart_ || manager_ == null) {
         return;
      }
      if (!event.isOn()) {
         return;
      }
      DataViewer viewer = studio_.displays().getActiveDataViewer();
      if (viewer == null) {
         return;
      }
    
      setupPlot();
   }

   // Actual new plot setup work
   private void setupPlot() {
      

      String plotmode = "Temperature reading";
      
      plots_ = 1;
      
      data_ = new XYSeries[channels_ * temps_];
      imagesReceived_ = 0; // new plot
      lastElapsedTimeMs_ = 0;
      XYSeriesCollection dataset = new XYSeriesCollection();
      for (int i = 0; i < temps_; i++) {
         int p = 0;
         data_[i * plots_ + p] = new XYSeries(sensor_id_[i]);
         data_[i * plots_ + p].setMaximumItemCount(maxPoints_);
         dataset.addSeries(data_[i * plots_ + p]);
      
      }
      
      if (graphFrame_ != null) {
         graphFrame_.setVisible(false);
         graphFrame_.dispose();
      }
      graphFrame_ = plotData(plotmode,
            dataset, "Time(sec)", "Value", plots_, backgroundEq_, 100, 100);
      graphFrame_.addWindowListener(new WindowAdapter() {
         public void windowClosing(WindowEvent e) {
            graphFrame_ = null;
            title_.setText("Ready");
         }
      });
      if (!delayedStart_) {
         title_.setText("Waiting for images...");
         dataProvider_.registerForEvents(RThandler_);
      } else {
         delayedStart_ = false;
      }
   }


   @Subscribe
   public void onNewImage(DataProviderHasNewImageEvent event) {
      processImage(event.getDataProvider(), event.getImage());
   }

   private void processImage(DataProvider dp, Image image) {
      // Kind of ugly way to autostart on new acquisition, new acquisition event seems too early
      if (delayedStart_) {
         dataProvider_ = dp;
         channels_ = dataProvider_.getSummaryMetadata().getChannelNameList().size();
         setupPlot();
      }
      if (!dp.equals(dataProvider_)) {
         return;
      }
      if (graphFrame_ == null) {
         return;
      }
      Double elapsedTimeMs = 10.0;
      elapsedTimeMs = image.getMetadata().getElapsedTimeMs(elapsedTimeMs);
      if (imagesReceived_ == 0) {
         lastElapsedTimeMs_ = -minPeriod_; // process first
      }
      // do not process images at more than 100 Hz
      if (missing_ > 0 || elapsedTimeMs - lastElapsedTimeMs_ >= minPeriod_) {
         lastElapsedTimeMs_ = elapsedTimeMs;
         int channel = image.getCoords().getChannel(); // 0..(n-1)
         if (channel >= channels_) {
            return;
         }
         if (channel == 0) {
            missing_ = channels_ - 1;
         } else {
            missing_--;
         }

         title_.setText("Data should be on the plot.(" + channel + "/" + imagesReceived_ + ")");

         ImageProcessor processor = studio_.data().ij().createProcessor(image);

         double v = 0;
         double bg = 0;

         int idx = 0; // follow series order with background gaps
         for (int i = 0; i < 3; i++) {
         
            try{
               v = Double.parseDouble(core_.getProperty("Device:TSP01", sensor_id_[i]));
            } catch (Exception e) {
               e.printStackTrace();
            }
            data_[0 + idx * plots_].add((double) elapsedTimeMs/1000, v, true);
            idx++; // Background ROIs do not have data series, just one at the end
         
         }
            
         
      }
      imagesReceived_++;
   }

   /**
    * Create a frame with a plot of the data given in XYSeries.
    *
    * @param title     Title of the plot
    * @param dataset   Data to be plotted
    * @param xTitle    Title of the x axis
    * @param yTitle    Title of the y axis
    * @param xLocation Window location on the screen (x)
    * @param yLocation Window Location on the screen (y)
    */
   public ChartFrame plotData(String title,
                                     XYSeriesCollection dataset,
                                     String xTitle,
                                     String yTitle,
                                     int plots,
                                     int bg,
                                     int xLocation,
                                     int yLocation) {
      // JFreeChart code
      JFreeChart chart = ChartFactory.createScatterPlot(title, // Title
            xTitle, // x-axis Label
            yTitle, // y-axis Label
            dataset, // Dataset
            PlotOrientation.VERTICAL, // Plot Orientation
            true, // Show Legend
            true, // Use tooltips
            false // Configure chart to generate URLs?
      );
      XYPlot plot = (XYPlot) chart.getPlot();
      int series = dataset.getSeriesCount();
      // Specific background series treatment
      if (bg >= 0) {
         series -= plots;
      }
      plot.setBackgroundPaint(Color.white);
      plot.setRangeGridlinePaint(Color.lightGray);
      XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
      renderer.setDefaultShapesVisible(true);
      renderer.setUseFillPaint(true);
      renderer.setSeriesFillPaint(0, Color.white);
      Shape circle = new Ellipse2D.Float(-2.0f, -2.0f, 4.0f, 4.0f);
      // Shape square = new Rectangle2D.Float(-2.0f, -2.0f, 4.0f, 4.0f);
      Paint paint;
      for (int i = 0; i < series; i++) {
         paint = plot.getDrawingSupplier().getNextPaint();
         renderer.setSeriesPaint(i, paint);
         renderer.setSeriesLinesVisible(i, true);
         renderer.setSeriesShape(i, circle, false);
         for (int p = 1; p < plots; p++) {
            renderer.setSeriesShape(++i, circle, false);
            renderer.setSeriesLinesVisible(i, true);
            renderer.setSeriesPaint(i, paint);
            renderer.setSeriesStroke(
                  i, new BasicStroke(
                        2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1.0f, new float[] {2.0f, (float) (4 * p)}, 0.0f
                  )
            );
         }
      }
      if (bg >= 0) {
         renderer.setSeriesPaint(series, Color.lightGray);
         renderer.setSeriesShape(series, circle, false);
         renderer.setSeriesLinesVisible(series, true);
         renderer.setSeriesStroke(
               series, new BasicStroke(
                     2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                     1.0f, new float[] {1.0f, 4.0f}, 0.0f));
         for (int p = 1; p < plots; p++) {
            renderer.setSeriesPaint(series + 1, Color.lightGray);
            renderer.setSeriesShape(series + 1, circle, false);
            renderer.setSeriesLinesVisible(series + p, true);
            renderer.setSeriesStroke(
                  series + 1, new BasicStroke(
                        2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1.0f, new float[] {1.0f, (float) (4 * (p + 1))}, 0.0f));
         }
      }

      MMChartFrame graphFrame = new MMChartFrame(title, chart, dataset, studio_, dataProvider_,
            this);
      graphFrame.modifyPopupMenu();
      graphFrame.getChartPanel().setMouseWheelEnabled(true);
      graphFrame.pack();
      graphFrame.setLocation(xLocation, yLocation);
      graphFrame.setSize(400, 300);
      WindowPositioning.setUpBoundsMemory(graphFrame, RTTemp.class, "plot");
      WindowPositioning.cascade(graphFrame, RTTemp.class);
      graphFrame.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
      graphFrame.setIconImage(Toolkit.getDefaultToolkit().getImage(
            RTTempFrame.class.getResource("/org/micromanager/icons/microscope.gif")));
      graphFrame.setVisible(true);
      return graphFrame;
   }

   public void setTile(String title) {
      title_.setText(title);
   }

}