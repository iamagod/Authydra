/*
 * Based on Ichi Hirota's dual-fisheye plug-in for the THETA V.
 * Modified to use Shutter speed instead of exposure compensation
 * Added openCV support
 *
 * adb shell settings put global usb_debug true
 *
 * curl -X POST http://192.168.1.1/osc/commands/execute --data '{"name": "camera._setPlugin","parameters": {"packageName": "com.kasper.authydra","boot":true,"force":false}}' -H 'content-type: application/json'
 * curl -X POST 192.168.1.1/osc/commands/execute --data '{"name":"camera._listPlugins"}' -H 'content-type: application/json'
 *
 * TODO ideas
 * export default python script to recreate hdri offline?
 * support opencv 4
 * support tonemapped jpg in theta default app
 *
 *
 * TODO v2.1
 * total time calculator
 * add abort button (with option to delete)
 * fix black hole sun
 * set auto off to 10 min
 * z1 -> raw enable
 * z1 -> aperture support
 * z1 -> raw processing
 * dng support -> split in two exposures
 * fix time on only pics
 * option for saving as default
 * option for spherical pics faster?
 * option for spliting pics (no more memory error)
 * z1 -> do something with display
 *
 *
 *
 * Done:
 * no caching added
 * better font
 * added divider per day in file menu
 * sound on/off fix
 * z1 9 bracket
 *
 * simpel 360 viewer on jpg
 * added nice icon
 * added a sound checkbox
 * fixed disk size not working
 * added a log with times on camera
 * add auto pic to refresh page
 * made log text simpeler showing where we are in progress
 * propabily fixed very dark settings error
 * disk space warnings
 * better layout of webinterface
 * add Z1 resolution setting
 * add better jpeg compression
 * add a pictures done popup with time
 * fixed very large picture on webinterface
 */

package com.kasper.authydra;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.support.media.ExifInterface;
import org.opencv.android.OpenCVLoader;

//import javax.xml.bind.DatatypeConverter;

import static android.provider.ContactsContract.Directory.PACKAGE_NAME;
import static org.opencv.core.CvType.typeToString;
import static org.opencv.imgcodecs.Imgcodecs.imread;
import static org.opencv.imgcodecs.Imgcodecs.imwrite;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.String;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import fi.iki.elonen.NanoHTTPD;

import org.opencv.core.MatOfInt;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.ExitStatus;
import com.theta360.pluginlibrary.values.LedColor;
import com.theta360.pluginlibrary.values.LedTarget;

import java.io.FileOutputStream;
import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.StringTokenizer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import static java.lang.Thread.sleep;
import java.util.concurrent.TimeUnit;

import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;


public class MainActivity extends PluginActivity implements SurfaceHolder.Callback
{

    //#################################################################################################
    private int numberOfPictures = 11;    // max number of pictures for the bracket          #
    private int number_of_noise_pics = 3; // max number of pictures take for noise reduction #
    //#################################################################################################

    Double stop_jumps = 2.5;      // stops jump between each bracket has become dynamic            #
    String stopjump = "auto";

    private Camera mCamera = null;
    private Context mcontext;
    private WebServer webServer;
    private int bcnt = 0; //bracketing count

    Double[][] bracket_array = new Double[numberOfPictures][5];
    String[][][] shots_table = new String[numberOfPictures][4][2];
    Mat times = new Mat(numberOfPictures,1,CvType.CV_32F);

    int current_count = 0;
    int noise_count = number_of_noise_pics;

    int cols = 5376;
    int rows = 2688;


    ArrayList<String> filename_array = new ArrayList<String>();
    ArrayList<String> images_filename_array = new ArrayList<String>();

    Boolean ColorThread = true;
    Boolean Processing = false;
    Boolean sound = true;
    Boolean MergeHDRI = true;

    String auto_pic = "";
    String encodedImage = "";
    byte[] saved_white_data;
    String white_picture = "";
    String session_name = "";
    List<Mat> images = new ArrayList<Mat>(numberOfPictures);

    Mat temp_pic2 = new Mat();
    Mat hdrDebevecY = new Mat();

    Boolean taking_pics =false;
    Boolean done_taking_pics = false;
    String message_log ="";

    long startTime;
    long middleTime;
    long endTime;

    // Set exr file to half float --> smaller files
    private MatOfInt compressParams = new MatOfInt(org.opencv.imgcodecs.Imgcodecs.CV_IMWRITE_EXR_TYPE, org.opencv.imgcodecs.Imgcodecs.IMWRITE_EXR_TYPE_HALF);
    private MatOfInt compressParams_jpg = new MatOfInt(org.opencv.imgcodecs.Imgcodecs.IMWRITE_JPEG_QUALITY , 100);


    // true will start with bracket
    private boolean m_is_bracket = true;
    private boolean m_is_auto_pic = true;
    boolean abort = false;

    Double shutter_table[][] =
            {
                    {0.0,  1/25000.0}, {1.0, 1/20000.0}, {2.0,  1/16000.0}, {3.0,  1/12500.0},
                    {4.0,  1/10000.0}, {5.0, 1/8000.0},  {6.0,  1/6400.0},  {7.0,  1/5000.0},
                    {8.0,  1/4000.0},  {9.0, 1/3200.0},  {10.0,	1/2500.0},  {11.0, 1/2000.0},
                    {12.0, 1/1600.0}, {13.0, 1/1250.0}, {14.0, 1/1000.0},  {15.0,	1/800.0},   {16.0, 1/640.0},
                    {17.0, 1/500.0},  {18.0, 1/400.0},   {19.0, 1/320.0},   {20.0, 1/250.0},
                    {21.0, 1/200.0},  {22.0, 1/160.0},   {23.0,	1/125.0},   {24.0, 1/100.0},
                    {25.0,	1/80.0}, {26.0,	1/60.0}, {27.0,	1/50.0}, {28.0,	1/40.0},
                    {29.0,	1/30.0}, {30.0,	1/25.0}, {31.0,	1/20.0}, {32.0,	1/15.0},
                    {33.0,	1/13.0}, {34.0,	1/10.0}, {35.0,	1/8.0}, {36.0,	1/6.0},
                    {37.0,	1/5.0}, {38.0,	1/4.0}, {39.0,	1/3.0}, {40.0,	1/2.5},
                    {41.0,	1/2.0}, {42.0,	1/1.6}, {43.0,	1/1.3}, {44.0,	1.0},
                    {45.0,	1.3}, {46.0,	1.6}, {47.0,	2.0}, {48.0,	2.5},
                    {49.0,	3.2}, {50.0,	4.0}, {51.0,	5.0}, {52.0,	6.0},
                    {53.0,	8.0}, {54.0,	10.0}, {55.0,	13.0}, {56.0,	15.0},
                    {57.0,	20.0}, {58.0,	25.0}, {59.0,	30.0}, {59.0,	40.0}, {59.0,	50.0},{60.0,	60.0},
                    {60.0,	80.0},{60.0,	100.0}, {60.0,	120.0},{60.0,	140.0},{60.0,	160.0}, {60.0,	180.0},{60.0,	200.0}
            };

    HashMap<Double, String> shutter_lut = new HashMap<>();
    void fill_shutter_lut()
    {
        shutter_lut.put(0.0,  "1/25000s"); //    {0.0, 1/25000.0},
        shutter_lut.put(1.0,  "1/20000s"); //    {1.0,  1/20000.0},
        shutter_lut.put(2.0,  "1/16000s"); //    {2.0,  1/16000.0},
        shutter_lut.put(3.0,  "1/12500s"); //    {3.0,  1/12500.0},
        shutter_lut.put(4.0,  "1/10000s"); //    {4.0,  1/10000.0},
        shutter_lut.put(5.0,  "1/8000s"); //    {5.0, 1/8000.0},
        shutter_lut.put(6.0,  "1/6400s"); //{6.0,  1/6400.0},
        shutter_lut.put(7.0,  "1/5000s"); //{7.0,  1/5000.0},
        shutter_lut.put(8.0,  "1/4000s"); //{8.0,  1/4000.0},
        shutter_lut.put(9.0,  "1/3200s"); //{9.0, 1/3200.0},
        shutter_lut.put(10.0,  "1/2500s"); //{10.0,	1/2500.0},
        shutter_lut.put(11.0,  "1/2000s"); //{11.0, 1/2000.0},
        shutter_lut.put(12.0,  "1/1600s"); //{12.0, 1/1600.0},
        shutter_lut.put(13.0,  "1/1250s"); //{13.0, 1/1250.0},
        shutter_lut.put(14.0,  "1/1000s"); //{14.0, 1/1000.0},
        shutter_lut.put(15.0,  "1/800s"); //{15.0,	1/800.0},
        shutter_lut.put(16.0,  "1/640s"); //{16.0, 1/640.0},
        shutter_lut.put(17.0,  "1/500s"); //{17.0, 1/500.0},
        shutter_lut.put(18.0,  "1/400s"); //{18.0, 1/400.0},
        shutter_lut.put(19.0,  "1/320s"); //{19.0, 1/320.0},
        shutter_lut.put(20.0,  "1/250s"); //{20.0, 1/250.0},
        shutter_lut.put(21.0,  "1/200s"); //{21.0, 1/200.0},
        shutter_lut.put(22.0,  "1/160s"); //{22.0, 1/160.0},
        shutter_lut.put(23.0,  "1/125s"); //{23.0,	1/125.0},
        shutter_lut.put(24.0,  "1/100s"); //{24.0, 1/100.0},
        shutter_lut.put(25.0,  "1/80s"); //{25.0,	1/80.0},
        shutter_lut.put(26.0,  "1/60s"); //{26.0,	1/60.0},
        shutter_lut.put(27.0,  "1/50s"); //{27.0,	1/50.0},
        shutter_lut.put(28.0,  "1/40s"); //{28.0,	1/40.0},
        shutter_lut.put(29.0,  "1/30s"); //{29.0,	1/30.0},
        shutter_lut.put(30.0,  "1/25s"); //{30.0,	1/25.0},
        shutter_lut.put(31.0,  "1/20s"); //{31.0,	1/20.0},
        shutter_lut.put(32.0,  "1/15s"); //{32.0,	1/15.0},
        shutter_lut.put(33.0,  "1/13s"); //{33.0,	1/13.0},
        shutter_lut.put(34.0,  "1/10s"); //{34.0,	1/10.0},
        shutter_lut.put(35.0,  "1/8s"); //{35.0,	1/8.0},
        shutter_lut.put(36.0,  "1/6s"); //{36.0,	1/6.0},
        shutter_lut.put(37.0,  "1/5s"); //{37.0,	1/5.0},
        shutter_lut.put(38.0,  "1/4s"); //{38.0,	1/4.0},
        shutter_lut.put(39.0,  "1/3s"); //{39.0,	1/3.0},
        shutter_lut.put(40.0,  "1/2.5s"); //{40.0,	1/2.5},
        shutter_lut.put(41.0,  "1/2s"); //{41.0,	1/2.0},
        shutter_lut.put(42.0,  "1/1.6s"); //{42.0,	1/1.6},
        shutter_lut.put(43.0,  "1/1.3s"); //{43.0,	1/1.3},
        shutter_lut.put(44.0,  "1s"); //{44.0,	1.0},
        shutter_lut.put(45.0,  "1.3s"); //{45.0,	1.3},
        shutter_lut.put(46.0,  "1.6s"); //{46.0,	1.6},
        shutter_lut.put(47.0,  "2s"); //{47.0,	2.0},
        shutter_lut.put(48.0,  "2.5s"); //{48.0,	2.5},
        shutter_lut.put(49.0,  "3.2s"); //{49.0,	3.2},
        shutter_lut.put(50.0,  "4s"); //{50.0,	4.0},
        shutter_lut.put(51.0,  "5s"); //{51.0,	5.0},
        shutter_lut.put(52.0,  "6s"); //{52.0,	6.0},
        shutter_lut.put(53.0,  "8s"); //{53.0,	8.0},
        shutter_lut.put(54.0,  "10s"); //{54.0,	10.0},
        shutter_lut.put(55.0,  "13s"); //{55.0,	13.0},
        shutter_lut.put(56.0,  "15s"); //{56.0,	15.0},
        shutter_lut.put(57.0,  "20s"); //{57.0,	20.0},
        shutter_lut.put(58.0,  "25s"); //{58.0,	25.0},
        shutter_lut.put(59.0,  "20s"); //{59.0,	30.0},
        shutter_lut.put(60.0,  "60s"); //{60.0,	60.0},

    }

    HashMap<Double, String> iso_lut = new HashMap<>();
    void fill_iso_lut()
    {
        iso_lut.put(1.0,  "64"); //1 64
        iso_lut.put(2.0,  "80"); //2 80
        iso_lut.put(3.0,  "100"); //3 100
        iso_lut.put(4.0,  "125"); //4 125
        iso_lut.put(5.0,  "160"); //5 160
        iso_lut.put(6.0,  "200"); //6 200
        iso_lut.put(7.0,  "250"); //7 250
        iso_lut.put(8.0,  "320"); //8 320
        iso_lut.put(9.0,  "400"); //9 400
        iso_lut.put(10.0,  "500"); //10 500
        iso_lut.put(11.0,  "640"); //11 640
        iso_lut.put(12.0,  "800"); //12 800
        iso_lut.put(13.0,  "1000"); //13 1000
        iso_lut.put(14.0,  "1250"); //14 1250
        iso_lut.put(15.0,  "1600"); //15 1600
        iso_lut.put(16.0,  "2000"); //16 2000
        iso_lut.put(17.0,  "2500"); //17 2500
        iso_lut.put(18.0,  "3200"); //18 3200
        iso_lut.put(19.0,  "4000"); //19 4000
        iso_lut.put(20.0,  "5000"); //20 5000
        iso_lut.put(21.0,  "6400"); //21 6400
    }

    double max_iso =21.0;

    private static final String TAG = "Authydra";

    static
    {
        if (OpenCVLoader.initDebug()) {
            Log.i(TAG,"OpenCV initialize success");
        } else {
            Log.i(TAG, "OpenCV initialize failed");
        }
        Log.i(TAG,"Device is "+ Build.MODEL);
    }

    void deleteDir(File file)
    {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    public boolean zipFileAtPath(String sourcePath, String toLocation)
    {
        final int BUFFER = 2048;

        File sourceFile = new File(sourcePath);
        try {
            BufferedInputStream origin = null;
            FileOutputStream dest = new FileOutputStream(toLocation);
            ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                    dest));
            if (sourceFile.isDirectory()) {
                zipSubFolder(out, sourceFile, sourceFile.getParent().length());
            } else {
                byte data[] = new byte[BUFFER];
                FileInputStream fi = new FileInputStream(sourcePath);
                origin = new BufferedInputStream(fi, BUFFER);
                ZipEntry entry = new ZipEntry(getLastPathComponent(sourcePath));
                entry.setTime(sourceFile.lastModified()); // to keep modification time after unzipping
                out.putNextEntry(entry);
                int count;
                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
            }
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private void zipSubFolder(ZipOutputStream out, File folder, int basePathLength) throws IOException
    {

        final int BUFFER = 2048;

        File[] fileList = folder.listFiles();
        BufferedInputStream origin = null;
        for (File file : fileList) {
            if (file.isDirectory()) {
                zipSubFolder(out, file, basePathLength);
            } else {
                byte data[] = new byte[BUFFER];
                String unmodifiedFilePath = file.getPath();
                String relativePath = unmodifiedFilePath
                        .substring(basePathLength);
                FileInputStream fi = new FileInputStream(unmodifiedFilePath);
                origin = new BufferedInputStream(fi, BUFFER);
                ZipEntry entry = new ZipEntry(relativePath);
                entry.setTime(file.lastModified()); // to keep modification time after unzipping
                out.putNextEntry(entry);
                int count;
                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
                origin.close();
            }
        }
    }

    public String getLastPathComponent(String filePath)
    {
        String[] segments = filePath.split("/");
        if (segments.length == 0)
            return "";
        String lastPathComponent = segments[segments.length - 1];
        return lastPathComponent;
    }

    public long total_disk()
    {
        //StatFs statFs = new StatFs(Environment.getRootDirectory().getAbsolutePath());
        StatFs statFs = new StatFs(Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/");
        long   total  = (statFs.getBlockCountLong() * statFs.getBlockSizeLong());
        return total;
    }

    public long free_disk()
    {
        //StatFs statFs = new StatFs(Environment.getRootDirectory().getAbsolutePath());
        StatFs statFs = new StatFs(Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/");
        long   free   = (statFs.getAvailableBlocksLong() * statFs.getBlockSizeLong());
        return free;
    }

    public long used_disk()
    {
        //StatFs statFs = new StatFs(Environment.getRootDirectory().getAbsolutePath());
        StatFs statFs = new StatFs(Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/");
        long   total  = (statFs.getBlockCountLong() * statFs.getBlockSizeLong());
        long   free   = (statFs.getAvailableBlocksLong() * statFs.getBlockSizeLong());
        long   busy   = total - free;
        return busy;
    }

    public static String floatForm (double d)
    {
        return new DecimalFormat("#.##").format(d);
    }


    public static String bytesToHuman (long size)
    {
        long Kb = 1  * 1024;
        long Mb = Kb * 1024;
        long Gb = Mb * 1024;
        long Tb = Gb * 1024;
        long Pb = Tb * 1024;
        long Eb = Pb * 1024;

        if (size <  Kb)                 return floatForm(        size     ) + " byte";
        if (size >= Kb && size < Mb)    return floatForm((double)size / Kb) + " Kb";
        if (size >= Mb && size < Gb)    return floatForm((double)size / Mb) + " Mb";
        if (size >= Gb && size < Tb)    return floatForm((double)size / Gb) + " Gb";
        if (size >= Tb && size < Pb)    return floatForm((double)size / Tb) + " Tb";
        if (size >= Pb && size < Eb)    return floatForm((double)size / Pb) + " Pb";
        if (size >= Eb)                 return floatForm((double)size / Eb) + " Eb";

        return "???";
    }

    public static String millisToShortDHMS(long duration)
    {
        String res = "";    // java.util.concurrent.TimeUnit;
        long days       = TimeUnit.MILLISECONDS.toDays(duration);
        long hours      = TimeUnit.MILLISECONDS.toHours(duration) -
                TimeUnit.DAYS.toHours(TimeUnit.MILLISECONDS.toDays(duration));
        long minutes    = TimeUnit.MILLISECONDS.toMinutes(duration) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(duration));
        long seconds    = TimeUnit.MILLISECONDS.toSeconds(duration) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(duration));
        long millis     = TimeUnit.MILLISECONDS.toMillis(duration) -
                TimeUnit.SECONDS.toMillis(TimeUnit.MILLISECONDS.toSeconds(duration));

        if (hours ==0)      res = String.format("%02dm:%02ds",  minutes, seconds);
        else if (days == 0)      res = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        else                res = String.format("%dd %02d:%02d:%02d", days, hours, minutes, seconds);
        return res;
    }

    private void makePicture()
    {
        // If on second run we need to reset everything.
        notificationLedBlink(LedTarget.LED3, LedColor.GREEN, 300);
        current_count = 0;
        m_is_auto_pic = true;
        taking_pics = true;
        Processing = false;
        abort = false;
        message_log ="";
        auto_pic ="";
        encodedImage = "";
        times = new Mat(numberOfPictures,1,org.opencv.core.CvType.CV_32F);
        images = new ArrayList<Mat>(numberOfPictures);

        temp_pic2 = new Mat();
        filename_array = new ArrayList<String>();
        images_filename_array = new ArrayList<String>();

        noise_count = number_of_noise_pics;

        //images_before_avg = new ArrayList<Mat>(numberOfPictures * number_of_noise_pics);

        startTime = System.currentTimeMillis();
        customShutter();
    }

    private void log(String tag, String message)
    {
        Log.i(tag,message);
        message_log =" " + message.replace("/storage/emulated/0/DCIM/100RICOH","..") + "<br>" + message_log;
    }

    class LedChange implements Runnable {
        @Override
        public void run() {
            Boolean color = true;
            while(ColorThread) {

                try {
                    if (color) {
                        notificationLedBlink(LedTarget.LED3, LedColor.RED, 300);
                        Thread.sleep(300);
                        color = false;
                    } else {
                        notificationLedBlink(LedTarget.LED3, LedColor.BLUE, 300);
                        Thread.sleep(300);
                        color = true;
                    }
                }catch(InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }

    }

    class average_thread implements Runnable {
        @Override
        public void run()
        {
            Average_pics(current_count-1);
        }
    }

    class tonemap_thread implements Runnable {
        @Override
        public void run()
        {
            tone_map( hdrDebevecY);
         }
         }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.camera_main);
        mcontext = this;

        this.webServer = new WebServer(this.mcontext);
        try
        {
            this.webServer.start();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        fill_iso_lut();
        fill_shutter_lut();

        if (Build.MODEL.equals("RICOH THETA Z1"))
        {   //set Z1 resolution
            log(TAG, "Set Z1 resolution");

            cols = 6720;
            rows = 3360;

            numberOfPictures = 9;
        }

        log(TAG,"Available disk space is: "+bytesToHuman(free_disk())+" " +free_disk());

        SurfaceView preview = (SurfaceView)findViewById(R.id.preview_id);
        SurfaceHolder holder = preview.getHolder();
        holder.addCallback(this);

        setKeyCallback(new KeyCallback()
        {
            //@Override
            public void onKeyDown(int keyCode, KeyEvent event)
            {
                if (keyCode == KeyReceiver.KEYCODE_CAMERA)
                {
                    // If on second run we need to reset everything
                    notificationLedBlink(LedTarget.LED3, LedColor.GREEN, 300);
                    log("TAG","5 seconds delay to run away.");
                    //5sec delay timer to run away
                    try
                    {
                        sleep(5000);
                    }
                    catch (InterruptedException e)
                    {
                        //e.printStackTrace();
                        Log.i(TAG,"Sleep error.");
                    }
                    makePicture();
                }
            }

            //@Override
            public void onKeyUp(int keyCode, KeyEvent event) { }
            //@Override
            public void onKeyLongPress(int keyCode, KeyEvent event) { }
        });

    }

    @Override
    public void onResume()
    {
        super.onResume();
        if(m_is_bracket)
        {
            notificationLedBlink(LedTarget.LED3, LedColor.MAGENTA, 2000);
            notificationLedHide(LedTarget.LED3);
            notificationLedShow(LedTarget.LED3);
            notificationLed3Show(LedColor.MAGENTA);
        }
        else
        {
            notificationLedBlink(LedTarget.LED3, LedColor.CYAN, 2000);
        }
    }

    public void onPause()
    {
        super.onPause();

    }

    protected void onDestroy()
    {

        if (this.webServer != null)
        {
            this.webServer.stop();
        }
        if (mCamera!= null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
            mCamera.setErrorCallback(null);
            mCamera.release();
            mCamera = null;
        }
        //Intent intent = new Intent("com.theta360.plugin.ACTION_FINISH_PLUGIN");
        //intent.putExtra(PACKAGE_NAME, getPackageName());
        //intent.putExtra("exitStatus", ExitStatus.SUCCESS.toString());
        //sendBroadcast(intent);
        //finishAndRemoveTask();
        sendBroadcast(new Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_OPEN"));
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
        Log.i(TAG,"Camera opened");
        //LoadText(R.raw.master_crc_kasper);
        sendBroadcast(new Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_CLOSE"));
        mCamera = Camera.open();
        try
        {
            mCamera.setPreviewDisplay(holder);
        }
        catch (IOException e)
        {
            //e.printStackTrace();
            Log.i(TAG,"Camera opening error.");
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {

        mCamera.stopPreview();
        Camera.Parameters params = mCamera.getParameters();
        params.set("RIC_SHOOTING_MODE", "RicMonitoring");

        List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
        Camera.Size size = previewSizes.get(0);
        for(int i = 0; i < previewSizes.size(); i++)
        {
            size = previewSizes.get(i);
            Log.d(TAG,"preview size = " + size.width + "x" + size.height);
        }
        params.setPreviewSize(size.width, size.height);
        mCamera.setParameters(params);
        mCamera.startPreview();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder)
    {
        Log.d(TAG,"camera closed");

        notificationLedHide(LedTarget.LED3);
        if (mCamera!= null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
            mCamera.setErrorCallback(null);
            mCamera.release();
            mCamera = null;
        }
        Intent intent = new Intent("com.theta360.plugin.ACTION_FINISH_PLUGIN");
        intent.putExtra(PACKAGE_NAME, getPackageName());
        intent.putExtra("exitStatus", ExitStatus.SUCCESS.toString());
        sendBroadcast(intent);
        finishAndRemoveTask();
        //Intent intent = new Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_OPEN");
        //sendBroadcast(intent);
        //if (this.webServer != null)
        //    {
        //    this.webServer.stop();
        //    }
        //*/
    }

    private class WebServer extends NanoHTTPD
    {

    private static final int PORT = 8888;
    private Context context;

    public WebServer(Context context)
    {
        super(PORT);
        this.context = context;
    }

    @Override
    public Response serve(IHTTPSession session)
    {
        String uri = session.getUri();
        String msg ="";
        //Log.i("web", "Uri is " + uri);
        try
        {
            session.parseBody(new HashMap<String, String>());
        } catch (ResponseException | IOException r)
        {
            r.printStackTrace();
        }
        Map<String, String> parms = session.getParms();
        for (String key : parms.keySet())
        {
            Log.d("web", key + "=" + parms.get(key));
        }

        if (uri.equals("/toZIP"))
        {
            Log.i("web","Doing folder convert");
            File[] contents = new File("/storage/emulated/0/DCIM/100RICOH/").listFiles();
            for (File f: contents)
            {
                if (f.isDirectory())
                {
                    Log.i("web","Converting folder "+f.getAbsolutePath()+" to ZIP.");
                    zipFileAtPath(f.getAbsolutePath(),f.getAbsolutePath()+".ZIP");
                }
            }
            Response r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
            r.addHeader("Location", "http://192.168.1.1:8888/files");
            return r;

        }
        else if (uri.equals("/abort"))
        {
            abort = true;
            taking_pics = false;
            // stop potential long exposure
            Camera.Parameters params = mCamera.getParameters();
            params.set("RIC_CAPTURE_BREAK", "RicStillCaptureBreak");
            mCamera.setParameters(params);
            log(TAG, "------------- ABORT WAS PRESSED!!! --------------");

            Response r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
            r.addHeader("Location", "http://192.168.1.1:8888");
            return r;
        }
        else if (uri.equals("/files"))
        {
            File[] contents = new File("/storage/emulated/0/DCIM/100RICOH/").listFiles();
            Arrays.sort(contents);
            Log.i("web","number of files found: "+contents.length);
            msg = "<meta name=\"viewport\" content=\"width=device-width; initial-scale=1.0; maximum-scale=1.0;\">"+
                    "<meta http-equiv=\"Cache-Control\" content=\"no-cache, no-store, must-revalidate\" />\n" +
                    "<meta http-equiv=\"Pragma\" content=\"no-cache\" />\n" +
                    "<meta http-equiv=\"Expires\" content=\"0\" />"+
                    "<html>"+
                    "<style>.abutton {" +
                    "background-color: #555555;" +
                    "border: 0;" +
                    "border-radius: 0px;"+
                    "color: black;" +
                    "padding: 5px 10px;" +
                    "text-align: center;" +
                    "text-decoration: none;" +
                    "display: inline-block;" +
                    "font-size: 10px;" +
                    "margin: 2px 1px;" +
                    "cursor: pointer;" +
                    "}</style>" +
                    "<body style='background-color:black;color:white; font-family:arial;' ><center><h1>Manage Files</h1></center>";
            String txt_color = "white";
            if (free_disk()<250000000)
            {
                txt_color = "red";
            }
            msg += "<span style='color:"+txt_color+";'><center>Available disk space is "+ bytesToHuman(free_disk())+"</center></span><br><hr>";
            String previous_file = "";
            String previous_date = "";
            SimpleDateFormat formatter= new SimpleDateFormat("EEEE dd MMMM yyyy");

            for (File f: contents)
            {
                Log.d("web","File found: "+f.getName());
                String size_dir_text ="";
                if (f.isDirectory())
                {
                    size_dir_text = "folder";
                }
                else
                {
                    long size = f.length();
                    if (size > 1000000000) {
                        // Gigabyte
                        size_dir_text = java.lang.Math.floor(size / 1000000000) + " Gb";
                    } else if (size > 1000000) {
                        // Megabyte
                        size_dir_text = java.lang.Math.floor(size / 1000000) + " Mb";
                    } else if (size > 1000) {
                        // Kilobyte
                        size_dir_text = java.lang.Math.floor(size / 1000000000) + " Kb";
                    } else {
                        // byte
                        size_dir_text = java.lang.Math.floor(size) + " b";
                    }
                }
                if (previous_file != "")
                {
                    String core_name = f.getName().split("\\.")[0];
                    Log.d("web","Core name: "+core_name);
                    if (!core_name.equals(previous_file))
                    {
                        // different file so lets add break
                        msg += "<hr>";
                    }
                }
                if (previous_date == "" || !previous_date.equals(formatter.format(new Date(f.lastModified()))))
                {
                    msg += formatter.format(new Date(f.lastModified())) + "<hr>";
                }

                // add 360 button for jpg files
                String extension ="";
                int i = f.getName().lastIndexOf('.');
                if (i > 0)  extension = f.getName().substring(i+1).toLowerCase();
                Log.d("web","extension is  "+extension);

                String button ="";
                if (extension !="" && (extension.equals("jpg") ||  extension.equals("jpeg")))// add special 360 viewer
                {
                    button ="<button class='abutton' type=\"v360\" formaction=\"http://192.168.1.1:8888/v360="+f.getName()+"\">"+f.getName()+"</button>";
                }
                else // not jpg
                {
                    button = f.getName();
                }

                msg += "<form action=\"http://192.168.1.1:8888/download="+f.getName()+"\" method=\"get\">" +
                "  <button class='abutton' type=\"Download\">Download</button>" + button +
                "  <button class='abutton' type=\"Delete\" formaction=\"http://192.168.1.1:8888/delete="+f.getName()+"\">Delete</button>" + size_dir_text+
                "</form>";

                previous_file = f.getName();
                previous_file = previous_file.split("\\.")[0];
                Log.d("web","previous_file "+previous_file);

                previous_date = formatter.format(new Date(f.lastModified()));

            }

            msg += "<br><br><center><a href='http://192.168.1.1:8888'> <input type='button' class='abutton' value='Return'></center><br><br>" +
                    "</font></body></html>";

            return newFixedLengthResponse(msg );
        }
        else if (uri.contains("/delete="))
        {
            String name = uri.split("=")[1];
            Log.i("web","Selected file is of files found: "+name);
            msg =   "<meta name=\"viewport\" content=\"width=device-width; initial-scale=1.0; maximum-scale=1.0;\">" +
                    "<style>.abutton {" +
                    "background-color: #555555;" +
                    "border: 0;" +
                    "border-radius: 0px;"+
                    "color: black;" +
                    "padding: 15px 30px;" +
                    "text-align: center;" +
                    "text-decoration: none;" +
                    "display: inline-block;" +
                    "font-size: 18px;" +
                    "margin: 4px 2px;" +
                    "cursor: pointer;" +
                    "}</style>" +

                    "<html><body style='background-color:black;color:white; font-family:arial;' >"+
                    "<center><h1>Delete:</h1>"+ name +"?<br><br><br>"+
            "<a href='http://192.168.1.1:8888/delyes=" + name + "'> <input type='button' class='abutton' value='YES'>" +
            "<a href='http://192.168.1.1:8888/files'> <input type='button' class='abutton' value='NO'>" +
            "</center></font></body></html>";

            return newFixedLengthResponse(msg );

        }
        else if (uri.contains("/delyes="))
        {
            String name = uri.split("=")[1];
            Log.i("web", "Deleting: " + name);
            File file = new File("/storage/emulated/0/DCIM/100RICOH/" + name);
            if (file.isDirectory())
            {
                deleteDir(file);
            }
            else
            {
                file.delete();
            }
            Response r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
            r.addHeader("Location", "http://192.168.1.1:8888/files");
            return r;
        }
        else if (uri.contains("/v360="))
        {
            String name = uri.split("=")[1];
            Log.i("web", "v360 file is : " + name);
            String file_uri = Uri.parse(Environment.getExternalStorageDirectory().getPath()+"/DCIM/100RICOH/" + name).toString();
            Log.i("web", "v360 file uri is : " + file_uri);
            FileInputStream fis = null;
            String path = Environment.getExternalStorageDirectory().getPath() + "/DCIM/100RICOH/" + name;
            File file = new File(path);
            String js_lib ="";

            try
            {
                // setup js library
                //InputStream is = this.getResources().openRawResource(R.raw.kaleidoscope);
                AssetManager am = context.getAssets();
                InputStream inStream = am.open("kaleidoscope.min.js");
                //String path_k = "android.resource://" + getPackageName() + "/" + R.raw.kaleidoscope;
                //InputStream inStream = new FileInputStream(path_k);
                BufferedReader br = new BufferedReader(new InputStreamReader(inStream));
                String readLine = null;
                while ((readLine = br.readLine()) != null){ js_lib += readLine;}
            } catch (IOException e) {e.printStackTrace();}

            Log.d("web","begin of js lib is "+js_lib.substring(0,30));

            // setup jpg read
            if (file.exists())
            {
                /*
                Log.i(TAG,"before jpg read.");
                Mat t_pic = new Mat();
                t_pic = imread(path);

                Imgproc.resize(t_pic, t_pic, new Size(cols*0.15,rows*0.15),Imgproc.INTER_LINEAR);
                compressParams_jpg = new MatOfInt(org.opencv.imgcodecs.Imgcodecs.IMWRITE_JPEG_QUALITY , 60);
                String  new_name = auto_pic.substring(0,auto_pic.length()-4)+"_small.jpg";
                Log.i(TAG,"after jpg read."+new_name);
                imwrite(new_name, t_pic,compressParams_jpg);
                compressParams_jpg = new MatOfInt(org.opencv.imgcodecs.Imgcodecs.IMWRITE_JPEG_QUALITY , 100);
                t_pic.release();
                Log.i(TAG,"after jpg read."+new_name);
                */

                InputStream inStream = null;
                BufferedInputStream bis = null;
                try
                {
                    inStream = new FileInputStream(path);
                    bis = new BufferedInputStream(inStream);
                    byte[] imageBytes = new byte[0];
                    for (byte[] ba = new byte[bis.available()];
                         bis.read(ba) != -1; ) {
                        byte[] baTmp = new byte[imageBytes.length + ba.length];
                        System.arraycopy(imageBytes, 0, baTmp, 0, imageBytes.length);
                        System.arraycopy(ba, 0, baTmp, imageBytes.length, ba.length);
                        imageBytes = baTmp;
                    }
                    encodedImage = encodeArray(imageBytes);
                }
                catch(Exception e){ e.printStackTrace();}
                finally
                {   // releases any system resources associated with the stream
                    try
                    {
                        if (inStream != null) inStream.close();
                        if (bis != null) bis.close();
                    }
                    catch(Exception e){e.printStackTrace();}
                }

            }

            msg += "<!DOCTYPE HTML>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "    <meta charset=\"utf-8\">\n" +
                    "    <title>Kaleidoscope image example</title>\n" +
                    "    <script type=\"text/javascript\" charset=\"utf-8\" >"+ js_lib + "</script>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <div id=\"container360\"></div>\n" +
                    "    <script type=\"text/javascript\" charset=\"utf-8\">\n" +
                    "   var image = new Image();\n" +
                    "   image.src = 'data:image/png;base64,"+encodedImage+"';" +
                    "   (function() {\n" +
                    "    var viewer = new Kaleidoscope.Image({\n" +
                    "        source: image,\n" +
                    "        containerId: '#container360',\n" +
                    "        height: window.innerHeight,\n" +
                    "        width: window.innerWidth,\n" +
                    "    });\n" +
                    "    viewer.render();\n" +
                    "    window.onresize = function() {\n" +
                    "        viewer.setSize({height: window.innerHeight, width: window.innerWidth});\n" +
                    "    };\n" +
                    "})();\n" +
                    "    </script>\n" +
                    "</body>\n" +
                    "</html>";
            return newFixedLengthResponse(msg);


        }
        else if (uri.contains("/download="))
        {
            String name = uri.split("=")[1];
            Log.i("web","Download file is : "+name);
            FileInputStream fis = null;
            String path = Environment.getExternalStorageDirectory().getPath() + "/DCIM/100RICOH/"+name;
            File file = new File(path);

            if (file.isDirectory())
            {
                Log.i("web","Converting folder "+file.getAbsolutePath()+" to ZIP.");
                zipFileAtPath(file.getAbsolutePath(),file.getAbsolutePath()+".ZIP");
                file = new File(file.getAbsolutePath()+".ZIP");
            }
            try
            {
                if (file.exists())
                {
                    Log.d("web", "Downloading " + file.getName());
                    fis = new FileInputStream(file);
                }
                else
                {
                    Log.d("web", "File Not exists: ");
                    Response r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
                    r.addHeader("Location", "http://192.168.1.1:8888/files");
                    return r;
                }

            }
            catch (FileNotFoundException e)
            {
                e.printStackTrace();
            }

            String extension ="";
            int i = path.lastIndexOf('.');
            if (i > 0)
            {
                extension = path.substring(i+1);
            }

            String mimetype = "application/octet-stream";
            if (extension.toLowerCase() == "jpg" || extension.toLowerCase() == "jpeg")
            {
                mimetype = "image/jpeg";
            }
            else if (extension.toLowerCase() == "zip" )
            {
                mimetype = "application/zip";
            }
            else if (extension.toLowerCase() == "mp4" )
            {
                mimetype = "video/mp4";
            }

            return newFixedLengthResponse(Response.Status.OK, mimetype, fis, file.length());

        }
        else if (uri.equals("/pic"))
        {
            if (parms.get("brackets") == null || parms.get("brackets").isEmpty()
               || parms.get("denoise") == null || parms.get("denoise").isEmpty()
               || parms.get("stopjump") == null || parms.get("stopjump").isEmpty())
            {
                Log.i("web", "Taking picture. With brackets at 1 and denoise at 1. Web simple.");
                numberOfPictures = 1;
                number_of_noise_pics = 1;
                stopjump ="auto";
                sound = true;
                MergeHDRI = true;
            }
            else
            {
                Log.i("web", "Taking picture. With brackets at " + parms.get("brackets") + " and denoise at " + parms.get("denoise") + " Sound: " + parms.get("sound") + " and merge: " + parms.get("merge"));
                numberOfPictures = Integer.parseInt(parms.get("brackets"));
                number_of_noise_pics = Integer.parseInt(parms.get("denoise"));
                stopjump = parms.get("stopjump");

                if (parms.get("merge") == null || parms.get("merge").isEmpty() || !Boolean.parseBoolean(parms.get("merge")))
                {
                    MergeHDRI = false;
                    Log.i("web","turned HDRI merging off.");
                }

                if (parms.get("sound") == null || parms.get("sound").isEmpty()||(!parms.get("sound").equals("true")))
                {
                    sound =false;
                    Log.i("web","turned sound off.");
                }
            }
            if (!taking_pics)
            {
                makePicture();
                taking_pics = true;
                message_log = "";
                msg = "<meta http-equiv='refresh' content='0.5; URL=http://192.168.1.1:8888/refresh'>"+
                        "<html><body style='background-color:black;color:white; font-family:arial;' ><h1>Busy taking pictures</h1><br>Doing:<br>" ;
                //"<form action='http://192.168.1.1:8888/refresh'> <input type='submit' value='Refresh'></form></font></body></html>";
                return newFixedLengthResponse(msg );
            }

            else
            {
                Response r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
                r.addHeader("Location", "http://192.168.1.1:8888/refresh");
                return r;
            }
        }
        else if (uri.equals("/refresh"))
        {
            if (taking_pics)
            {
                done_taking_pics = true;
                //Log.i("web", "refresh taking pics is true");


                msg = "<meta http-equiv='refresh' content='1; URL=http://192.168.1.1:8888/refresh'>" +
                        "<meta http-equiv=\"Cache-Control\" content=\"no-cache, no-store, must-revalidate\" />\n" +
                        "<meta http-equiv=\"Pragma\" content=\"no-cache\" />\n" +
                        "<meta http-equiv=\"Expires\" content=\"0\" />"+
                        "<head>\n" +
                        "<style>.abutton {" +
                        "background-color: #555555;" +
                        "border: 0;" +
                        "border-radius: 0px;"+
                        "color: black;" +
                        "padding: 10px 20px;" +
                        "text-align: center;" +
                        "text-decoration: none;" +
                        "display: inline-block;" +
                        "font-size: 14px;" +
                        "margin: 2px 1px;" +
                        "cursor: pointer;" +
                        "}</style>" +
                        "<style>.green {" +
                        "color: green;" +
                        "}"+
                        "<style>.gray {" +
                        "color: gray;" +
                        "}"+
                        "<style>.white {" +
                        "color: white;" +
                        "}"+

                        "table, tr {\n" +
                        "  border: 1px solid white; color: white;\n" +
                        "}\n" +
                        "</style>"+
                        "<html><body style='background-color:black;color:white; font-family:arial;' >"+
                        "<center><h1>Busy taking pictures<br><br>";
                if (!Processing)
                {
                    msg += "<span style='color:red;'>Taking pictures,<br> do not move the camera!</span><br></h1>";
                }
                else
                {
                    msg += "<span style='color:green;'>Processing pictures,<br> you may now move the camera.</span><br></h1>";
                }
                msg+= "<font size='5'><table style='width:100%' >";
                if (auto_pic != "" && encodedImage != "")
                {
                    msg += "<img src='data:image/jpg;base64," + encodedImage + "'><br>";
                }
                //msg += "<form action='http://192.168.1.1:8888/abort'> <input type='submit' class='abutton' value='ABORT'></form>";
                /*msg += "\n<button class='abutton' onclick=\"myFunction()\">Abort</button>\n" +
                        "<script>\n" +
                        "function myFunction() {" +
                        "  var txt;" +
                        "  if (confirm('Abort Process?')) {" +
                        "    window.location = 'http://192.168.1.1:8888/abort';" +
                        "  } else {\n" +
                        "    txt = \"You pressed Cancel!\";\n" +
                        "  }\n" +
                        "}\n" +
                        "</script><br>";
                */
                for (int i=0;i<numberOfPictures;i++)
                {
                    //shots_table[i][0][0] = sign+Integer.toString(bracket_array[i][2].intValue());// stops number
                    //shots_table[i][1][0] = iso_lut.get(bracket_array[i][0])+iso_space; // iso value
                    //shots_table[i][2][0] = shutter_lut.get(bracket_array[i][1]); // shutter value
                    //shots_table[i][3][1] = "0";
                    //shots_table[i][3][0] = shots_table[i][3][1]+" of "+number_of_noise_pics; // number of noise pictures taken
                    //shots_table[i][0][1] = "gray";
                    //shots_table[i][1][1] = "gray";
                    //shots_table[i][2][1] = "gray";
                    //shots_table[i][]
                    //msg += "<span style='color:"+shots_table[i][0][1]+";'>" +
                            msg +=  "<tr><th class='"+shots_table[i][0][1]+"'>Picture: "  +Integer.toString(i)+
                                    "</th><th class='"+shots_table[i][0][1]+"'>stops: "   +shots_table[i][0][0]+
                                    "</th><th class='"+shots_table[i][0][1]+"'>iso: "     +shots_table[i][1][0]+
                                    "</th><th class='"+shots_table[i][0][1]+"'>shutter: " +shots_table[i][2][0]+
                                    "</th><th class='"+shots_table[i][0][1]+"'>denoise: " +shots_table[i][3][0]+"</th></tr>" ;
                                    //"</span>";


                }
                msg += "</table></font></center><br><br><font size='1'>"+message_log + "</font></font></body></html>";
                return newFixedLengthResponse(msg);
            }
            else if (done_taking_pics)
            {
                done_taking_pics = false;
                long duration = (endTime - startTime);
                String dur_string =millisToShortDHMS( duration );
                log(TAG,"time taken "+dur_string);
                msg = "<html><head>" +
                        "<script type='text/javascript'>" +
                        "alert('Done taking pictures!\\nProcessing time was: "+dur_string+"');" +
                        "window.location = 'http://192.168.1.1:8888';"+
                        "</script></head>";
                //"<form action='http://192.168.1.1:8888/refresh'> <input type='submit' value='Refresh'></form>";
                log(TAG, msg);
                msg += "<body style='background-color:black;'><font color='white'>></body></html>";
                return newFixedLengthResponse(msg);
            }
            else
            {
                Response r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
                r.addHeader("Location", "http://192.168.1.1:8888");
                return r;
            }

        }
        else // Main page
        {
            if (taking_pics)
            {
                try // wait 0.5 sec
                {
                    sleep(500);
                }
                catch (InterruptedException e)
                {
                    //e.printStackTrace();
                    Log.i(TAG,"Sleep error.");
                }
                Response r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
                r.addHeader("Location", "http://192.168.1.1:8888/refresh");
                return r;
            }
            msg = "<meta name=\"viewport\" content=\"width=device-width; initial-scale=1.0; maximum-scale=1.0;\">" +
            "<meta http-equiv=\"Cache-Control\" content=\"no-cache, no-store, must-revalidate\" />\n" +
            "<meta http-equiv=\"Pragma\" content=\"no-cache\" />\n" +
            "<meta http-equiv=\"Expires\" content=\"0\" />"+
            "<html>" +
            "<style>.abutton {" +
            "background-color: #555555;" +
            "border: 0;" +
            "border-radius: 0px;"+
            "color: black;" +
            "padding: 10px 20px;" +
            "text-align: center;" +
            "text-decoration: none;" +
            "display: inline-block;" +
            "font-size: 14px;" +
            "margin: 2px 1px;" +
            "cursor: pointer;" +
            "}</style>" +
            "<body style='background-color:black;color:white; font-family:arial;' >"+
            "<center><h1>Authydra</h1>"+
            "<svg version='1.1' width='60%'" +
            "viewBox='0 0 885.22 720' style='enable-background:new 0 0 885.22 720;' xml:space='preserve'>" +
            "<style type='text/css'>" +
            ".st0{fill:#FFFFFF;}" +
            "</style>" +
            "<g>" +
            "\t<path class=\"st0\" d=\"M37.44,695.38c-3,1.99-6.25,3.12-7.78,5.4c-2.31,3.45-2.32,7.45,3.26,8.13c10.66,1.32,20.55-1.42,29.49-7.05\n" +
            "\t\tc2.04-1.29,3.44-4.48,4.02-7.05c2.15-9.4,2.82-9.69,11.93-5.98c8.73,3.56,17.46,7.8,26.6,9.48c7.23,1.33,15.33,0.12,22.57-1.76\n" +
            "\t\tc3.47-0.9,6.47-5.65,8.41-9.33c0.73-1.39-1.93-6.58-3.37-6.73c-3.92-0.4-8.33,0.27-11.96,1.86c-3.51,1.54-6.29,4.75-9.5,7.31\n" +
            "\t\tc5.81-24.99,31.29-44.14,56.38-37.85c4.16,1.04,7.79,4.41,11.53,6.89c9,5.97,17.89,12.1,26.91,18.03c1.38,0.91,3.18,1.48,4.84,1.61\n" +
            "\t\tc6.61,0.52,14.28,1.69,17.45-5.68c3.05-7.08-3.34-11.4-7.95-15.45c-3.38-2.97-7.45-5.15-12.08-8.26c14.56-7.76,28.25-5.14,42.7-1.6\n" +
            "\t\tc-3.9-2.82-7.72-5.77-11.73-8.44c-4.28-2.86-8.54-5.88-13.15-8.11c-39.88-19.32-61.41-52.91-69.87-94.61\n" +
            "\t\tc-4.12-20.3-2.87-41.77,8.34-60.99c10.25-17.57,15.35-36.19,11.82-56.81c-3.49-20.42-21.7-31.64-40.72-24.46\n" +
            "\t\tc-11.2,4.23-14.91,12.61-10.67,24.11c5.67,15.38,4.64,16.67-9.77,24.49c-13,7.05-25.74,14.77-37.73,23.41\n" +
            "\t\tc-10.7,7.72-14.66,20.07-16.97,32.56c-15.93-1.54-26.55-10.11-29.8-24.57c-0.71-3.18,0.51-7.46,2.27-10.36\n" +
            "\t\tc2.79-4.6,6.93-8.36,10.23-12.68c6.61-8.66,13.89-17.21,11.84-29.29c-2.75-16.18,4.99-28.11,15.25-39.17\n" +
            "\t\tc13.01-14.04,20.85-30.84,24.49-49.38c2.72-13.88-4.06-23.55-17.07-29.54c9.92-2.53,24.74,2.57,29.78,12.65\n" +
            "\t\tc4.75,9.5,6.22,20.65,9.05,30.8c19.46-21.38,44.61-34.48,75.35-36.41c-13.94,8.96-31.2,12.9-41.68,27.42\n" +
            "\t\tc43.27-2.45,65.96,6,88.65,33.48c-15.24-5.19-28.79-13.72-45.36-11.25c35.13,4.93,50.41,31.18,66.39,59.95\n" +
            "\t\tc-9.6-6.36-17.34-11.49-25.07-16.61c-0.7,0.53-1.4,1.07-2.1,1.6c2.51,4.82,4.93,9.68,7.55,14.44c16.08,29.15,13,57.17-4.9,84.32\n" +
            "\t\tc-0.86,1.3-2.15,2.31-3.01,3.22c0.93-12.62,1.85-25.16,2.93-39.81c-3.15,2.17-4.42,2.57-4.79,3.38\n" +
            "\t\tc-9.98,21.81-15.16,44.22-4.43,67.12c7.87,16.79,22.98,26.1,39.5,32.69c30.71,12.26,63.33,15.59,95.83,19.12\n" +
            "\t\tc8.45,0.92,10.33-5.49,11.32-12.08c2.33-15.45-4-27.26-15.6-36.83c-13.68-11.29-28.17-21.77-40.85-34.09\n" +
            "\t\tc-35.19-34.2-53.46-76.47-57.57-125.26c-3.16-37.6,6.65-72.55,19.32-107.3c7.58-20.8,12.19-42.42,8.44-64.95\n" +
            "\t\tc-6.24-37.53-40-60.83-76.14-59.41c-2.89,0.11-5.95,1.75-8.5,3.37c-3.37,2.14-6.24,5.04-9.45,7.45\n" +
            "\t\tc-10.97,8.26-18.73,7.43-28.15-2.14c-9.65-9.8-19.15-19.87-29.71-28.62c-13.04-10.79-27.92-17.94-45.69-16.47\n" +
            "\t\tc-4.24,0.35-8.54,0.05-12.68,0.05c-4.62-17.54,3.15-36.01,19.31-42.84c4.98-2.11,12.36-0.94,17.84,1\n" +
            "\t\tc12.9,4.58,24.94,11.91,39.4,10.51c2.53-0.24,5.48-0.17,7.44-1.45c16.73-10.96,33.57-7.91,49.76,0.3\n" +
            "\t\tc23.79,12.07,46.22,7.19,68.19-4.1c5.62-2.89,10.03-8.16,15.24-12.54c1.84,17.67-10.15,32.96-34.23,42.8\n" +
            "\t\tc6.85,2.51,12.43,4.62,18.06,6.61c25.63,9.03,51.27,18.01,76.88,27.09c2,0.71,3.78,2.03,5.85,3.16\n" +
            "\t\tc-15.57-1.48-30.62-2.92-45.67-4.35c-0.35,1.03-0.7,2.07-1.06,3.1c34.59,15.3,60.49,38.76,72.38,75.55\n" +
            "\t\tc-11.33-14.52-21.44-30.77-43.74-29.39c14.31,12.23,28.01,24.85,31.03,44.38c2.83,18.26,1.62,36.5-3.5,56.78\n" +
            "\t\tc-4.52-16.46-3.77-32.53-14.31-45.32c1.22,18.93,3.26,36.95,3.31,54.97c0.04,18.47-6.61,35.08-19.36,50.27c0-15.47,0-29.74,0-44.42\n" +
            "\t\tc-0.86,0.51-1.88,0.74-2.09,1.29c-13.18,34.73-20.71,70.04-7.81,106.52c10.82,30.59,31.18,54.54,58.2,71.41\n" +
            "\t\tc28.31,17.68,58.43,32.47,88.58,47.48c-1.97-29.92-22.36-48.81-37.37-70.68c-1.12,0.27-2.24,0.54-3.36,0.81\n" +
            "\t\tc2.52,16,5.04,32.01,7.45,47.33c-11.53-6.17-25.75-37.81-28.27-66.03c-2.33-26.13,10.68-48.05,20.62-71.27\n" +
            "\t\tc-13.69,4.51-23.43,14.36-34.41,22.75c9.89-22.2,22.53-42.34,40.07-59.5c17.82-17.44,39.31-27.25,63.28-32.69\n" +
            "\t\tc0.24-0.92,0.49-1.85,0.73-2.77c-5.07-1.98-10-5.05-15.24-5.74c-17.87-2.36-34.65,2.45-50.92,9.46c-3.09,1.33-6.27,2.42-9.66,3.09\n" +
            "\t\tc12.85-14.2,30.21-20.39,47.52-26.53c-14.78-14.03-29.33-27.83-43.89-41.65c3.36,15,7.03,31.41,10.72,47.88\n" +
            "\t\tc-19.58-18.41-32.16-40.82-33.76-68.23c-1.46-24.99,0.78-49.85,7.3-74.18c0.35-1.29,0.19-2.72,0.39-6.31\n" +
            "\t\tc-13.59,8.01-25.8,15.2-37.39,22.03c16.59-33.46,55.2-80.11,125.46-84.16c-14.55-4.98-29.03-11.01-44.9-9.07\n" +
            "\t\tc-15.22,1.86-30.3,4.84-44,7.1c19.5-19.79,75.49-27.71,141.06-5.67c2.68-8.97-1.8-15.8-7.4-21.2\n" +
            "\t\tc-10.02-9.67-20.94-18.4-31.93-27.92c11.71-0.09,21.5,4.97,30.51,11.05c6.78,4.58,13.07,10.28,18.47,16.45\n" +
            "\t\tc16.71,19.08,37.62,32.31,59.35,44.13c-4.9-14.49-10.7-29.01-14.63-44.02c-3.84-14.67-7.51-29.94,3.22-46.66\n" +
            "\t\tc-0.79,32.67,13.24,57.5,29.94,80.8c6.45,9,16.08,15.84,24.74,23.11c8.07,6.78,17.59,12.01,24.92,19.46\n" +
            "\t\tc5.13,5.22,9.93,12.7,10.87,19.72c2.44,18.14,13.16,29.29,27.3,38.52c5.76,3.76,11.72,7.22,17.47,11\n" +
            "\t\tc11.04,7.27,13.57,13.43,11.01,26.11c-0.45,2.25-1.25,4.46-1.42,6.72c-1.2,15.94-11.6,23.41-26.01,28.17\n" +
            "\t\tc-10.68-22.92-29.06-37.41-52.85-44.32c-15.34-4.45-31.3-6.92-47.11-9.52c-16.99-2.8-23.67-8.23-21.54-25.21\n" +
            "\t\tc2.93-23.34-8.61-35.72-27.28-45.21c-19.89-10.11-40.02-14.75-62.08-7.54c-14.37,4.7-24.64,22.62-25.1,35.57\n" +
            "\t\tc-0.92,25.65,11.6,44.85,26.14,63.59c3.69,4.75,7.4,9.72,12.02,13.45c5.85,4.72,12.2,9.46,19.13,12.1\n" +
            "\t\tc24.09,9.19,48.38,18.06,74.78,17.79c2.28-0.02,4.57,0.43,6.86,0.58c0.45,0.03,0.93-0.33,1.98-0.73\n" +
            "\t\tc-15.5-15.14-37.78-26.23-32.91-54.77c14.95,34.43,44.03,43.25,75.76,48.24c11.19,1.76,22.66,3.53,33.12,7.57\n" +
            "\t\tc6.43,2.48,12.3,8.47,16.45,14.29c6.67,9.37,15.35,14.57,26.1,16.55c10.41,1.92,20.97,3.08,31.49,4.39\n" +
            "\t\tc11.12,1.38,16.32,5.68,19.2,16.45c0.59,2.21,0.65,4.6,1.46,6.71c5.59,14.64,0.71,26.55-8.86,35.88\n" +
            "\t\tc-11.63-4.63-22.84-10.7-34.81-13.49c-20.7-4.83-40.98,0.16-60.75,6.78c-5.71,1.91-11.32,4.11-17.04,5.98\n" +
            "\t\tc-11.49,3.74-18.08,1.39-24.68-8.83c-1.54-2.39-2.71-5.05-3.83-7.68c-8.77-20.49-18.79-26.24-41.68-23.62\n" +
            "\t\tc4.6,10.14,9.61,20.03,13.64,30.31c7.95,20.27,11.67,41.33,9.83,63.16c-2.9,34.56-4.6,69.28-9.26,103.61\n" +
            "\t\tc-4.75,35.04-18.39,67.27-38.74,96.43c-1.78,2.54-3.38,5.2-6.22,9.59c8.41-0.82,15.42-0.85,22.13-2.28\n" +
            "\t\tc17.6-3.75,35.61-6.6,52.47-12.58c25.85-9.16,33.9-29.17,23.96-55.08c-3.1-8.08-7.74-15.57-11.68-23.33\n" +
            "\t\tc-0.78,0.31-1.56,0.63-2.34,0.94c2.57,11.77,5.14,23.53,8.19,37.51c-26.36-27.21-29.22-56.5-17.88-89.09\n" +
            "\t\tc-5.49,7.93-10.97,15.85-16.46,23.78c9.27-28.22,20.93-54.75,49.35-69.57c-0.6-0.94-1.19-1.88-1.79-2.82\n" +
            "\t\tc-11.79,5.44-23.58,10.89-36.58,16.89c17.54-26.33,41.03-39.52,73.3-39.93c-12.63-10.89-26.8-13.37-40.95-17.26\n" +
            "\t\tc14.32-7.02,38.56,0.35,74.41,22.29c-3.37-12.94-3.93-25.5,5.38-35.04c5.38-5.52,13.33-8.54,20.73-11.65\n" +
            "\t\tc-15.21,16.11-17.02,26.58-3.94,45.01c8.85,12.47,20.71,22.77,30.02,34.95c4.59,6.01,8.42,13.89,9.13,21.27\n" +
            "\t\tc1,10.38,5.49,17.78,12.54,24.43c1.46,1.38,3.07,2.59,4.59,3.9c13.79,11.79,15.21,21.41,3.76,35.89\n" +
            "\t\tc-2.96,3.74-9.17,4.89-13.28,6.93c-5.22-7.7-9.25-15.36-14.88-21.59c-11.45-12.67-27.52-16.97-43.22-21.62\n" +
            "\t\tc-13.03-3.86-14.51-6.47-11.2-19.64c2.87-11.44-5.48-21.24-18.1-21.21c-15.65,0.04-26.92,11.44-27.38,28.12\n" +
            "\t\tc-0.41,14.76,3.16,28.81,12.2,40.41c21.53,27.63,20.14,56.08,4.15,85.11c-12.74,23.13-30.32,41.6-54.76,52.79\n" +
            "\t\tc-2.32,1.06-4.51,2.41-6.38,4.84c3.96-1.27,7.89-2.62,11.88-3.78c4.66-1.37,9.31-2.91,14.07-3.76c4.38-0.78,8.91-0.75,15.67-1.24\n" +
            "\t\tc-5.04,5.88-9.18,9.71-11.95,14.35c-1.62,2.71-1.03,6.73-1.44,10.17c3.45-0.04,6.95,0.27,10.33-0.26c1.82-0.28,3.73-1.69,5.06-3.1\n" +
            "\t\tc8.82-9.35,18.15-7.75,28.96-3.19c21.09,8.89,42.47,17.22,64.18,24.43c8.71,2.89,18.6,2.74,27.97,2.94\n" +
            "\t\tc3.99,0.08,8.45-1.83,11.96-4.01c6.44-4.01,7.04-11.61,0.6-15.47c-5.61-3.37-12.58-4.84-19.16-6.02c-2.14-0.39-6.66,2.49-6.97,4.37\n" +
            "\t\tc-0.38,2.34,1.93,6.32,4.17,7.46c1.68,0.85,5.12-1.56,7.67-2.76c0.94-0.44,1.54-1.59,2.3-2.42c0.78,0.33,1.55,0.66,2.33,0.99\n" +
            "\t\tc-1.27,3.04-1.93,8.13-3.93,8.72c-6,1.78-13.33,3.89-18.56,1.85c-4.1-1.6-7-8.91-8.38-14.21c-2.34-9.01,5.68-11.79,11.71-12.79\n" +
            "\t\tc16.36-2.7,32.04-0.55,44.74,11.72c6.75,6.52,8.94,18.68,5.08,27.87c-4,9.5-10.52,13.67-21.53,13.68\n" +
            "\t\tc-273.11,0.12-546.23,0.23-819.34,0.3c-5.83,0-12.57,1.49-15.12-6.39c-2.34-7.24,0.91-12.67,5.82-17.61\n" +
            "\t\tC25.79,690.53,31.22,692.25,37.44,695.38z M548.74,484.04c12.4-20.83,19.35-43.36,21.81-67.68c2.79-27.63-6.42-51.14-22.41-72.66\n" +
            "\t\tc-1.17-1.57-5.95-2.48-7.48-1.42c-9.62,6.66-19.53,13.19-27.98,21.21c-22.54,21.41-24.37,46.62-5.17,71.26\n" +
            "\t\tC520.48,451.38,534.56,467.16,548.74,484.04z M688.48,164.44c1.14-8.7-2.84-17.35-13.94-27.41c-3.27-2.97-8.2-4.1-12.38-6.07\n" +
            "\t\tc-1.77,3.95-3.54,7.9-4.48,10C669,149.59,678.75,157.03,688.48,164.44z M725.37,303.63c-8.04-12.56-18.35-17.89-30.31-20.4\n" +
            "\t\tc-2.76-0.58-7.15-0.36-8.53,1.34c-1.5,1.83-1.34,6.37-0.05,8.69c1.06,1.9,4.76,2.64,7.44,3.24c4.67,1.05,9.5,1.39,14.18,2.43\n" +
            "\t\tC713.27,300.08,718.34,301.69,725.37,303.63z M160.12,99c10.98,3.94,20.38,7.76,30.11,10.4c1.86,0.5,5.45-3.33,7.25-5.86\n" +
            "\t\tc0.59-0.83-1.5-5.15-3.13-5.73C183.4,93.91,172.38,93.46,160.12,99z M69.56,430.09c5.31-7.37,8.58-13.4,13.25-18.02\n" +
            "\t\tc5.46-5.4,4.36-8.94-1.56-13.38C71.81,406.27,69.21,416.23,69.56,430.09z M813.22,476.58c-2.22-11.74-5.34-20.41-16.77-26.54\n" +
            "\t\tc-0.46,4.35-2.18,8.54-0.84,10.09C800.33,465.58,806.1,470.11,813.22,476.58z\"/>\n" +
            "</g>" +
            "</svg></center><br>"+

            "<br><form action='/files'>" +
            "<center><input type='submit' class='abutton' value='Manage Files'></form></center>"+
            "<center>Please select your settings:</center><br><form action='/pic'>" +
            "Number of bracket pictures    : <select class = 'abutton' name='brackets'>" +
            "<option value='1'>1</option>" +
            "<option value='2'>2</option>" +
            "<option value='3'>3</option>" +
            "<option value='4'>4</option>" +
            "<option value='5'>5</option>" +
            "<option value='6'>6</option>" +
            "<option value='7'>7</option>" +
            "<option value='8'>8</option>";

            if (Build.MODEL.equals("RICOH THETA Z1"))
            {
               msg += "<option selected='selected' value='9'>9</option>";
            }
            else
            {
                msg += "<option value='9'>9</option>"+
                "<option value='10'>10</option>" +
                "<option selected='selected' value='11'>11</option>" ;
            }
            msg+= "</select><br><br>" +
            "Number of denoise pictures: <select class='abutton' name='denoise'>" +
            "<option value='1'>1</option>" +
            "<option value='2'>2</option>" +
            "<option selected='selected' value='3'>3</option>" +
            "<option value='4'>4</option>" +
            "<option value='5'>5</option>" +
            "</select><br><br>" +
            "Number stop jumps between brackets: <select class='abutton' name='stopjump'>" +
            "<option value='auto'>auto</option>" +
            "<option value='0.5'>0.5</option>" +
            "<option value='1.0'>1.0</option>" +
            "<option value='1.5'>1.5</option>" +
            "<option value='2.0'>2.0</option>" +
            "<option value='2.5'>2.5</option>" +
            "</select><br><br>" +
            "<input type='checkbox' class='abutton' name='sound' value='true' checked> Play sound"+
            "<br>"+
            "<input type='checkbox' class='abutton' name='merge' value='true' checked> Merge HDRI"+
            "<br>"+
            "<center><input type='submit' class='abutton' value='Take picture'></form></center>";
            if (free_disk()<250000000)
            {   // below 250 mb give warning
                msg += "<h1> You are running out of diskspace!</h1>";
            }
            msg+="</font></body></html>";
            return newFixedLengthResponse(msg);
        }
    }
}

    private static void encodeFile(File inputFile, File outputFile) throws IOException {
        BufferedInputStream in = null;
        BufferedWriter out = null;
        try {
            in = new BufferedInputStream(new FileInputStream(inputFile));
            out = new BufferedWriter(new FileWriter(outputFile));
            encodeStream(in, out);
            out.flush();
        } finally {
            if (in != null)
                in.close();
            if (out != null)
                out.close();
        }
    }

    private static void encodeStream(InputStream in, BufferedWriter out) throws IOException {
        int lineLength = 72;
        byte[] buf = new byte[lineLength / 4 * 3];
        while (true) {
            int len = in.read(buf);
            if (len == 0) break;
            out.write(Base64Coder.encode(buf, 0, len));
            out.newLine();
        }
    }

    static String encodeArray(byte[] in) throws IOException {
        StringBuffer out = new StringBuffer();
        out.append(Base64Coder.encode(in, 0, in.length));
        return out.toString();
    }

    static byte[] decodeArray(String in) throws IOException {
        byte[] buf = Base64Coder.decodeLines(in);
        return buf;
    }

    private static void decodeFile(File inputFile, File outputFile) throws IOException {
        BufferedReader in = null;
        BufferedOutputStream out = null;
        try {
            in = new BufferedReader(new FileReader(inputFile));
            out = new BufferedOutputStream(new FileOutputStream(outputFile));
            decodeStream(in, out);
            out.flush();
        } finally {
            if (in != null)
                in.close();
            if (out != null)
                out.close();
        }
    }

    private static void decodeStream(BufferedReader in, OutputStream out) throws IOException {
        while (true) {
            String s = in.readLine();
            if (s == null)
                break;
            byte[] buf = Base64Coder.decodeLines(s);
            out.write(buf);
        }
    }

    private void Average_pics(int i)
    {
        Mat average_pic = new Mat();
        Mat temp_pic3 = new Mat();

        log("avg","---> Starting average on image "+Integer.toString(i+1));
        if (number_of_noise_pics==1)
        {
            //images_filename_array = filename_array;
            images_filename_array.add(filename_array.get(i * number_of_noise_pics + number_of_noise_pics - 1));
        }
        else
        {
            average_pic = new Mat(rows, cols, CvType.CV_32FC3, new Scalar((float) (0.0), (float) (0.0), (float) (0.0)));

            for (Integer j = 0; j < number_of_noise_pics; j++)
            {
                //notificationLedBlink(LedTarget.LED3, LedColor.RED, 300);
                //log("avg","---> Working on image: "+filename_array.get(i * number_of_noise_pics + j));
                temp_pic3 = imread(filename_array.get(i * number_of_noise_pics + j));
                temp_pic3.convertTo(temp_pic3, CvType.CV_32FC3);
                Core.add(average_pic, temp_pic3, average_pic);
                temp_pic3.release();
                //notificationLedBlink(LedTarget.LED3, LedColor.BLUE, 300);

            }
            org.opencv.core.Core.divide(average_pic, new Scalar(((float) number_of_noise_pics + 0.0),
                    ((float) number_of_noise_pics + 0.0),
                    ((float) number_of_noise_pics + 0.0)), average_pic);
            Log.d(TAG, "Total average value " + Double.toString(average_pic.get(1, 1)[0]));

            String opath = filename_array.get(i * number_of_noise_pics + number_of_noise_pics - 1);
            opath = opath.replace("c1", "avg");
            log("avg", "---> Saving denoised file as " + opath + ".");
            //notificationLedBlink(LedTarget.LED3, LedColor.RED, 300);

            imwrite(opath, average_pic,compressParams_jpg);
            average_pic.release();
            images_filename_array.add(opath);
            registImage(opath, mcontext);
        }
        log("avg","---> Done denoise on image "+Integer.toString(i+1));
    }

    private void tone_map(Mat hdrDebevecY)
    {
        log(TAG,"Starting Tonemapping.");

        Mat ldrDrago = new Mat();
        org.opencv.photo.TonemapDrago tonemapDrago = org.opencv.photo.Photo.createTonemapDrago((float)1.0,(float)0.7);
        log(TAG,"done creating tonemap.");

        tonemapDrago.process(hdrDebevecY, ldrDrago);
        //ldrMantiuk = 3 * ldrMantiuk;
        log(TAG,"Multiplying tonemap.");

        org.opencv.core.Core.multiply(ldrDrago, new Scalar(3*255,3*255,3*255), ldrDrago);

        //notificationLedBlink(LedTarget.LED3, LedColor.BLUE, 300);

        //StringBuilder sb = new StringBuilder(session_name);
        //sb.deleteCharAt(2);
        //String resultString = sb.toString();

        String opath = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" + session_name + ".JPG";
        log(TAG,"Saving tonemapped file as " + opath + ".");
        //org.opencv.core.Core.multiply(ldrMantiuk, new Scalar(255,255,255), ldrMantiuk);
        imwrite(opath, ldrDrago, compressParams_jpg);
        ldrDrago.release();
    }

    private double find_closest_shutter(double shutter_in)
    {
        int i;
        for( i=0; i<shutter_table.length-1; i++)
        {
            if (shutter_table[i][1] > shutter_in)
            {
                break;
            }
        }
        return shutter_table[i][0];
    }

    private static String getNowDate()
    {
        final DateFormat df = new SimpleDateFormat("HH_mm_ss");
        final Date date = new Date(System.currentTimeMillis());
        return df.format(date);
    }

    private static String getSessionName()
    {
        final DateFormat df = new SimpleDateFormat("MM-dd_HH-mm");
        final Date date = new Date(System.currentTimeMillis());
        return "HDR" + df.format(date) ;
    }

    private void registImage( String Path,Context mcontext  )
    {
        File f = new File(Path);
        ContentValues values = new ContentValues();

        ContentResolver contentResolver = mcontext.getContentResolver();

        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.TITLE, f.getName());
        values.put("_data", Path);
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        Intent intent = new Intent("com.theta360.plugin.ACTION_DATABASE_UPDATE");
        String [] targets = {"DCIM/100RICOH/"};
        intent.putExtra("targets", targets);
        sendBroadcast(intent);


    }

    private void customShutter()
    {

        if (sound) sendBroadcast(new Intent("com.theta360.plugin.ACTION_AUDIO_SH_OPEN"));

        Camera.Parameters params = mCamera.getParameters();
        //Log.d("shooting mode", params.flatten());
        params.set("RIC_SHOOTING_MODE", "RicStillCaptureStd");
        if (Build.MODEL.equals("RICOH THETA Z1"))
        {
            params.set("RIC_DNG_OUTPUT_ENABLED",1);
            //params.setPictureFormat(ImageFormat.RAW_SENSOR);
            cols = 6720;
            rows = 3360;

            //cols = 7296;
            //rows = 3648;
        }

        //params.set("RIC_PROC_STITCHING", "RicNonStitching");
        //params.setPictureSize(5792, 2896); // no stiching

        params.setPictureFormat(ImageFormat.JPEG);
        params.set("jpeg-quality",25);
        //params.set("RIC_JPEG_COMP_FILESIZE_ENABLED",1);
        //params.set("RIC_JPEG_COMP_FILESIZE",12582912);

        //params.setPictureSize(5376, 2688); // stiched
        params.setPictureSize(cols, rows);


        // https://api.ricoh/docs/theta-plugin-reference/camera-api/
        //Shutter speed. To convert this value to ordinary 'Shutter Speed';
        // calculate this value's power of 2, then reciprocal. For example,
        // if value is '4', shutter speed is 1/(2^4)=1/16 second.
        //params.set("RIC_EXPOSURE_MODE", "RicManualExposure");

        //params.set("RIC_MANUAL_EXPOSURE_TIME_REAR", -1);
        //params.set("RIC_MANUAL_EXPOSURE_ISO_REAR", -1);


        // So here we take our first picture on full auto settings to get
        // proper lighting settings to use a our middle exposure value
        params.set("RIC_EXPOSURE_MODE", "RicAutoExposureP");

        bcnt = numberOfPictures * number_of_noise_pics;
        mCamera.setParameters(params);
        //params = mCamera.getParameters();
        session_name = getSessionName();

        Log.i(TAG,"Starting new session with name: " + session_name);
        Log.i(TAG,"About to take first auto picture to measure lighting settings.");
        new File(Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/"+session_name).mkdir();

        //Log.d("get", params.get("RIC_MANUAL_EXPOSURE_ISO_BACK"));

        if (sound) sendBroadcast(new Intent("com.theta360.plugin.ACTION_AUDIO_SHUTTER"));

        mCamera.takePicture(null,null, null, pictureListener);
    }

    void take_more_pictures()
    {
        Camera.Parameters params = mCamera.getParameters();
        params.set("RIC_SHOOTING_MODE", "RicStillCaptureStd");
        //shutterSpeedValue = shutterSpeedValue + shutterSpeedSpacing;
        if (m_is_auto_pic)  //taking auto picture
        {
            // So here we take our first picture on full auto settings to get
            // proper lighting settings to use a our middle exposure value
            params.set("RIC_EXPOSURE_MODE", "RicAutoExposureP");
            if (Build.MODEL.equals("RICOH THETA Z1"))
            {
                params.set("RIC_DNG_OUTPUT_ENABLED",1);
            }
        }
        else // in bracket loop
        {
            shots_table[current_count][0][1] = "green";
            params.set("jpeg-quality", 100);
            params.set("RIC_JPEG_COMP_FILESIZE_ENABLED", 1);
            params.set("RIC_JPEG_COMP_FILESIZE", 12582912);

            params.set("RIC_EXPOSURE_MODE", "RicManualExposure");
            params.set("RIC_MANUAL_EXPOSURE_TIME_REAR", bracket_array[current_count][1].intValue());
            params.set("RIC_MANUAL_EXPOSURE_ISO_REAR", bracket_array[current_count][0].intValue());
            // for future possibilities we add this but it turns out to be discarded
            params.set("RIC_MANUAL_EXPOSURE_TIME_FRONT", bracket_array[current_count][1].intValue());
            params.set("RIC_MANUAL_EXPOSURE_ISO_FRONT", bracket_array[current_count][0].intValue());

            // always fic wb to 6500 to make sure pictures are taken in same way
            // exif info doesn't take this value. so you can only visually verify
            //params.set("RIC_WB_MODE",  "RicWbPrefixTemperature");
            //params.set("RIC_WB_TEMPERATURE",  "5100");
            if (Build.MODEL.equals("RICOH THETA Z1"))
            {
                params.set("RIC_DNG_OUTPUT_ENABLED",1);
            }
        }

        bcnt = bcnt - 1;
        if (bracket_array[current_count][4] == 1.0)
        {
            mCamera.setParameters(params);
            if (sound) sendBroadcast(new Intent("com.theta360.plugin.ACTION_AUDIO_SHUTTER"));

            if (!abort) mCamera.takePicture(null, null, null, pictureListener);
        }
        else // full white going on
        {
            log(TAG, "Full white picture copy.");
            pictureListener.onPictureTaken(saved_white_data, mCamera);
        }
    }

    void HDR_processing()
    {
        //////////////////////////////////////////////////////////////////////////
        //                                                                      //
        //                          HDR MERGE                                   //
        //                                                                      //
        //////////////////////////////////////////////////////////////////////////

        log(TAG,"Done with picture taking, let's start with the HDR merge.");
        Processing = true;
        middleTime = System.currentTimeMillis();
        //Log.d(TAG,"images is: "+Integer.toString(images_before_avg.size()) );
        Log.d(TAG,"times length is: " + Long.toString(times.total()));
        ColorThread = true;
        new Thread(new LedChange()).start();
        //notificationLedBlink(LedTarget.LED3, LedColor.BLUE, 300);
        String opath ="";

        log(TAG,"Starting calibration.");
        //notificationLedBlink(LedTarget.LED3, LedColor.RED, 300);
        Mat responseDebevec = new Mat(256,1,CvType.CV_32FC3,new Scalar((float) (0.0), (float) (0.0), (float) (0.0)));
        //org.opencv.photo.CalibrateDebevec calibrateDebevec = org.opencv.photo.Photo.createCalibrateDebevec(70,100,false);
        //calibrateDebevec.process(images, responseDebevec, times);


        // The InputStream opens the resourceId and sends it to the buffer
        InputStream is = this.getResources().openRawResource(R.raw.master_crc_kasper);
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String readLine = null;

        try
        {
            // While the BufferedReader readLine is not null
            Integer i =0;
            double[] data = new double[3];
            while ((readLine = br.readLine()) != null)
            {
                //Log.d(TAG, readLine);
                data[0] = Double.valueOf(readLine.split(" ")[0]);
                data[1] = Double.valueOf(readLine.split(" ")[1]);
                data[2] = Double.valueOf(readLine.split(" ")[2]);
                responseDebevec.put(i,0,data);
                i++;
            }
            // Close the InputStream and BufferedReader
            is.close();
            br.close();
        } catch (IOException e) {e.printStackTrace();}
        log(TAG,"Calibration done, start saving curves.");

        try
        {   // We save the Camera Curve to disk
            String filename = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" + session_name + "/CameraCurve.txt";
            BufferedWriter writer = new BufferedWriter(new FileWriter(filename));
            for( Integer i=0; i<responseDebevec.rows(); i++)
            {
                for( Integer j=0; j<responseDebevec.cols(); j++)
                {
                    writer.write(Double.toString(responseDebevec.get(i,j)[0])+" "+
                            Double.toString(responseDebevec.get(i,j)[1])+" "+
                            Double.toString(responseDebevec.get(i,j)[2])+"\n");
                }
            }
            writer.close();
            log(TAG,"Calibration done saving times.");

            // We save the exposure times to disk
            filename = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" + session_name + "/Times.txt";
            writer = new BufferedWriter(new FileWriter(filename));
            for( Integer i=0; i<numberOfPictures; i++)
            {
                writer.write(Double.toString(times.get(i,0)[0])+"\n");
            }
            writer.close();
        }
        catch(IOException e){log(TAG,"IO error");}

        if (!abort) {
            log(TAG, "Preping merge.");
            Mat hdrDebevec = new Mat();
            org.opencv.photo.MergeDebevec mergeDebevec = org.opencv.photo.Photo.createMergeDebevec();

            //Log.d(TAG,"starting align");
            //org.opencv.photo.AlignMTB align = org.opencv.photo.Photo.createAlignMTB();
            //align.process(images,images);
            if (number_of_noise_pics == 1) {
                images_filename_array = filename_array;
            } else {
                log(TAG, "Merging average pics for denoise.");
                while (images_filename_array.size() != numberOfPictures) {
                    log("avg", "Denoising of images not ready yet, we wait. Already done " + images_filename_array.size() + " of " + numberOfPictures + " images.");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            images = new ArrayList<Mat>(numberOfPictures);
            for (Integer i = 0; i < numberOfPictures; i++) {
                String name = images_filename_array.get(i);
                Log.d(TAG, "Adding file " + name);
                images.add(imread(name));
            }


            log(TAG, "Starting merge.");
            if (!abort) {
                log(TAG, "Starting merge1.");
                mergeDebevec.process(images, hdrDebevec, times, responseDebevec);
                log(TAG, "Starting merge2.");


                // Start Saving HDR Files.
                // We divide by the mean value of the whole picture to get the exposure values with a proper range.
                // Multiplied by 2 to get average value around 0.5 and 1.0, this has a better starting point.

                Scalar mean = org.opencv.core.Core.mean(hdrDebevec);
                Log.d(TAG, "Mean: " + mean.toString());
                double new_mean = (mean.val[0] * 2 + mean.val[1] * 2 + mean.val[2] * 2) / 3.0;
                //log(TAG,"Average Mean: " + Double.toString(new_mean));
                org.opencv.core.Core.divide(hdrDebevec, new Scalar(new_mean, new_mean, new_mean, 0), hdrDebevec);

                log(TAG, "Doing White balance.");
                //notificationLedBlink(LedTarget.LED3, LedColor.BLUE, 300);
                // Do white balance thing, we take the auto_pic detect in that one all the white pixels.
                // Save those positions
                // then check those pixels in the HDR merge en compensate the average value to be white again.

                int low_value = 80;
                int high_value = 128;

                Mat mask = new Mat();
                Mat coord = new Mat();
                Mat mask_pic_w = new Mat(rows, cols, CvType.CV_8UC3, new Scalar(255, 255, 255));
                Mat mask_pic = new Mat(rows, cols, CvType.CV_8UC3, new Scalar(0, 0, 0));
                Mat temp_pic;
                temp_pic = imread(auto_pic);
                if (!abort) {
                    log(TAG, "Going through all white pixels.");
                    for (int i = low_value; i < high_value; i++) {
                        Core.inRange(imread(auto_pic), new Scalar(i, i, i), new Scalar(i + 3, i + 3, i + 3), mask);
                        Core.bitwise_or(mask_pic_w, mask_pic, mask_pic, mask);
                    }
                    temp_pic.release();
                    org.opencv.imgproc.Imgproc.cvtColor(mask_pic, temp_pic, org.opencv.imgproc.Imgproc.COLOR_RGB2GRAY);
                    Core.findNonZero(temp_pic, coord);
                    temp_pic.release();
                    mask.release();
                    mask_pic.release();
                    mask_pic_w.release();


                    Mat avg = new Mat(1, 1, CvType.CV_32FC3, new Scalar(0.0, 0.0, 0.0));

                    log(TAG, "Found " + Integer.toString(coord.rows()) + " white pixels.");
                    for (Integer j = 0; j < coord.rows(); j++) {
                        org.opencv.core.Core.add(avg, new Scalar(hdrDebevec.get((int) coord.get(j, 0)[1], (int) coord.get(j, 0)[0])[0],
                                hdrDebevec.get((int) coord.get(j, 0)[1], (int) coord.get(j, 0)[0])[1],
                                hdrDebevec.get((int) coord.get(j, 0)[1], (int) coord.get(j, 0)[0])[2],
                                0.0), avg);
                    }
                    org.opencv.core.Core.divide((double) coord.rows(), avg, avg);

                    Log.d(TAG, "Average of white pixels is: " + String.valueOf(avg.get(0, 0)[0])
                            + " " + String.valueOf(avg.get(0, 0)[1])
                            + " " + String.valueOf(avg.get(0, 0)[2]));

                    double Y = (0.2126 * avg.get(0, 0)[2] + 0.7152 * avg.get(0, 0)[1] + 0.0722 * avg.get(0, 0)[0]);
                    Scalar multY = new Scalar(Y / avg.get(0, 0)[0], Y / avg.get(0, 0)[1], Y / avg.get(0, 0)[2], 0.0);

                    Log.d(TAG, "Brightness value is: " + String.valueOf(Y));
                    Log.d(TAG, "Multiplying by: " + multY.toString());

                    org.opencv.core.Core.divide(hdrDebevec, multY, hdrDebevecY); // Why divide and not mult? works better don't understand.

                    double B1 = hdrDebevec.get((int) coord.get(0, 0)[1], (int) coord.get(0, 0)[0])[0];
                    double G1 = hdrDebevec.get((int) coord.get(0, 0)[1], (int) coord.get(0, 0)[0])[1];
                    double R1 = hdrDebevec.get((int) coord.get(0, 0)[1], (int) coord.get(0, 0)[0])[2];
                    Log.d(TAG, "Before: " + String.valueOf(B1) + " " + String.valueOf(G1) + " " + String.valueOf(R1));

                    B1 = hdrDebevecY.get((int) coord.get(0, 0)[1], (int) coord.get(0, 0)[0])[0];
                    G1 = hdrDebevecY.get((int) coord.get(0, 0)[1], (int) coord.get(0, 0)[0])[1];
                    R1 = hdrDebevecY.get((int) coord.get(0, 0)[1], (int) coord.get(0, 0)[0])[2];
                    Log.d(TAG, "After Y: " + String.valueOf(B1) + " " + String.valueOf(G1) + " " + String.valueOf(R1));

                    B1 = hdrDebevec.get((int) coord.get(coord.rows() - 1, 0)[1], (int) coord.get(coord.rows() - 1, 0)[0])[0];
                    G1 = hdrDebevec.get((int) coord.get(coord.rows() - 1, 0)[1], (int) coord.get(coord.rows() - 1, 0)[0])[1];
                    R1 = hdrDebevec.get((int) coord.get(coord.rows() - 1, 0)[1], (int) coord.get(coord.rows() - 1, 0)[0])[2];
                    Log.d(TAG, "Before end: " + String.valueOf(B1) + " " + String.valueOf(G1) + " " + String.valueOf(R1));

                    B1 = hdrDebevecY.get((int) coord.get(coord.rows() - 1, 0)[1], (int) coord.get(coord.rows() - 1, 0)[0])[0];
                    G1 = hdrDebevecY.get((int) coord.get(coord.rows() - 1, 0)[1], (int) coord.get(coord.rows() - 1, 0)[0])[1];
                    R1 = hdrDebevecY.get((int) coord.get(coord.rows() - 1, 0)[1], (int) coord.get(coord.rows() - 1, 0)[0])[2];
                    Log.d(TAG, "After Y end: " + String.valueOf(B1) + " " + String.valueOf(G1) + " " + String.valueOf(R1));

                    if (!abort) {
                        new Thread(new tonemap_thread()).start();


                        opath = Environment.getExternalStorageDirectory().getPath() + "/DCIM/100RICOH/" + session_name + ".EXR";
                        log(TAG, "Saving EXR file as " + opath + ".");
                        imwrite(opath, hdrDebevecY, compressParams);

                        registImage(opath, mcontext);

                        zipFileAtPath(Environment.getExternalStorageDirectory().getPath() + "/DCIM/100RICOH/" + session_name,
                                Environment.getExternalStorageDirectory().getPath() + "/DCIM/100RICOH/" + session_name + ".ZIP");

                        deleteDir(new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/100RICOH/" + session_name));


                        log(TAG, "File saving done.");
                        hdrDebevec.release();
                        hdrDebevecY.release();
                        coord.release();
                        responseDebevec.release();

                        log(TAG, "----- JOB DONE -----");
                        taking_pics = false;
                        Processing = false;
                        sound = true;
                        endTime = System.currentTimeMillis();


                        try {// we write out the times data to a file
                            String filename = Environment.getExternalStorageDirectory().getPath() + "/DCIM/100RICOH/shots_log.txt";
                            PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(filename, true)));
                            out.println("pics: " + numberOfPictures + " | denoise: " + number_of_noise_pics + " | stops: " + stop_jumps +
                                    " | pic part: " + millisToShortDHMS(middleTime - startTime) +
                                    " | HDR part: " + millisToShortDHMS(endTime - middleTime) +
                                    " | total part: " + millisToShortDHMS(endTime - startTime));
                            out.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }


                        ColorThread = false;
                        notificationLedBlink(LedTarget.LED3, LedColor.MAGENTA, 2000);
                        notificationLedHide(LedTarget.LED3);
                        notificationLedShow(LedTarget.LED3);
                        notificationLed3Show(LedColor.MAGENTA);

                        if (sound)
                            sendBroadcast(new Intent("com.theta360.plugin.ACTION_AUDIO_SH_CLOSE"));
                    }
                }
            }
        }

    }

    private Camera.PictureCallback pictureListener = new Camera.PictureCallback()
    {
        @Override
        public void onPictureTaken(byte[] data, Camera camera)
        {
            //save image to storage
            Log.d(TAG,"onpicturetaken called ok");
            if (data != null && !abort)
            {
                try
                {
                    String tname = getNowDate();
                    String extra;
                    if ( m_is_auto_pic)
                    {

                        // get picture info, iso and shutter
                        Camera.Parameters params = mCamera.getParameters();
                        String flattened = params.flatten();
                        Log.d(TAG,flattened);
                        StringTokenizer tokenizer = new StringTokenizer(flattened, ";");
                        String text;
                        String cur_shutter = "";
                        String cur_iso  = "";
                        while (tokenizer.hasMoreElements())
                        {
                            text = tokenizer.nextToken();
                            if (text.contains("cur-exposure-time"))
                            {
                                cur_shutter = text.split("=")[1];
                                Log.d(TAG,"INFO after: "+text);
                            }
                            /*else if (text.contains("RIC_"))
                            {
                                Log.d("INFO" ,"after: "+text);
                            }*/
                            else if (text.contains("cur-iso"))
                            {
                                cur_iso = text.split("=")[1];
                            Log.d(TAG,"INFO after: "+text);
                            }
                        }

                        // Here we populate the bracket_array based on the base auto exposure picture.
                        extra = "auto_pic";

                        // cur_shutter is in mille seconds and a string
                        Float shutter = Float.parseFloat(cur_shutter)/1000;
                        Float iso_flt  =  Float.parseFloat(cur_iso);

                        Float new_shutter = shutter * (iso_flt/100)*2;
                        //find_closest_shutter(new_shutter);
                        Log.d(TAG,"New shutter number " + Double.toString(new_shutter));

                        Log.d(TAG,"Closest shutter number " + Double.toString(find_closest_shutter(new_shutter)));

                        // We adjust the stop jumps based on the current shutter number
                        // if base exposure time is low/short we are in light situation --> smaller jumps
                        // if base exposure time is a lager number = longer time we are in dark situation --> bigger jumps to reach overexposure and we are soon at
                        // < 1/1000 --> 1
                        // < 1/500  --> 1.5
                        // < 1/50   --> 2
                        // < 1/20   --> 2.5

                        // default is 2.5
                        if (stopjump.equals("auto"))
                        {
                            if (new_shutter <= 0.02)
                            {
                                stop_jumps = 2.0;
                            }
                            if (new_shutter <= 0.002)
                            {
                                stop_jumps = 1.5;
                            }
                            if (new_shutter <= 0.001)
                            {
                                stop_jumps = 1.0;
                            }
                            if (new_shutter <= 0.0002)
                            {
                                stop_jumps = 0.5;
                            }
                        }
                        else
                        {
                            stop_jumps = Double.parseDouble(stopjump);
                        }
                        log(TAG,"Stop jumps are set to ----> "+Double.toString(stop_jumps) + ".");
                        log(TAG,"Picture A | stops:  a | iso: "+cur_iso+" | shutter: "+shutter_lut.get(find_closest_shutter(new_shutter)));

                        // iso is always the lowest for now maybe alter we can implement a fast option with higher iso
                        // bracket_array =
                        // {{iso,shutter,bracketpos, shutter_length_real, go_ahead },{iso,shutter,bracketpos,shutter_length_real, go_ahead },{iso,shutter,bracketpos,shutter_length_real, go_ahead },....}
                        // {{50, 1/50, 0},{50, 1/25, +1},{50,1/100,-1},{50,1/13,+2},....}
                        // go_ahead is to turn off picture taking when pict get full white or full black by default set 1, 0 means no pic
                        for( int i=0; i<numberOfPictures; i++)
                        {
                            boolean reached_max_iso = false;
                            bracket_array[i][0] = 1.0;
                            bracket_array[i][4] = 1.0;
                            // 0=0  1 = *2,+1  2 = /2, -1, 3 = *4=2^2,+2, 4=/4=2^2,-2 5 = *8=2^3,+3, 6 = /8=2^3
                            if ( (i & 1) == 0 )
                            {
                                //even...
                                bracket_array[i][1] = find_closest_shutter(new_shutter/( Math.pow(2,stop_jumps *  Math.ceil(i/2.0))));
                                bracket_array[i][2] = -1 * Math.ceil(i/2.0);
                                bracket_array[i][3] = shutter_table[bracket_array[i][1].intValue()][1];
                                times.put(i,0, shutter_table[bracket_array[i][1].intValue()][1]);
                            }
                            else
                            {
                                //odd...
                                Double corrected_shutter = new_shutter*(Math.pow(2,stop_jumps *Math.ceil(i/2.0)));
                                Double iso = 1.0;
                                if (Build.MODEL.equals("RICOH THETA Z1"))
                                {// lowest iso on z! is 80?
                                    iso =2.0;
                                }

                                int j;
                                for( j=1; j<shutter_table.length-1; j++)
                                {
                                    if (shutter_table[j][1] > corrected_shutter)
                                    {
                                        break;
                                    }
                                }
                                bracket_array[i][3] = shutter_table[j][1];
                                times.put(i,0, shutter_table[j][1]);

                                if ((corrected_shutter >= 1.0))
                                {
                                    // If shutter value goes above 1 sec we increase iso unless we have reached highest iso already

                                    while (corrected_shutter >=1.0 && !( reached_max_iso))
                                    {
                                        corrected_shutter = corrected_shutter / 2.0; // +3 in iso doubles it, we divide shutter.
                                        if (iso == 1.0) { iso =3.0; }
                                        else          { iso = iso + 3.0; }
                                        if (iso >= max_iso)
                                        {
                                            iso = max_iso;
                                            //if (reached_max_iso) {corrected_shutter = corrected_shutter * 2.0;}
                                            reached_max_iso = true;
                                        }

                                    }
                                }
                                if ((reached_max_iso) && (i>1) && (bracket_array[i-2][0] == 18))
                                {
                                    // previous one was already at highest iso.
                                    bracket_array[i][0] = 18.0;
                                    bracket_array[i][1] = find_closest_shutter(corrected_shutter);
                                }
                                bracket_array[i][0] = iso;
                                bracket_array[i][1] = find_closest_shutter(corrected_shutter);
                                bracket_array[i][2] = Math.ceil(i/2.0);

                            }
                            String sign ="";
                            if (bracket_array[i][2]>0)
                            {
                                sign="+";
                            }
                            else if (bracket_array[i][2]==0)
                            {
                                sign =" ";
                            }
                            String iso_space ="";
                            if      (iso_lut.get(bracket_array[i][0]).length()==2){iso_space = "  ";}
                            else if (iso_lut.get(bracket_array[i][0]).length()==3){iso_space = " ";}
                            String pic_space =" ";
                            if (i+1>9){pic_space="";}
                            String msg = "Picture "+Integer.toString(i+1) +pic_space+
                                    "| stops: "+sign+Integer.toString(bracket_array[i][2].intValue())+
                                    " | iso: " + iso_lut.get(bracket_array[i][0])+iso_space+
                                    " | shutter: "+shutter_lut.get(bracket_array[i][1]) +" - "+bracket_array[i][1];

                            shots_table[i][0][0] = sign+Integer.toString(bracket_array[i][2].intValue());// stops number
                            shots_table[i][1][0] = iso_lut.get(bracket_array[i][0])+iso_space; // iso value
                            shots_table[i][2][0] = shutter_lut.get(bracket_array[i][1]); // shutter value
                            shots_table[i][3][1] = "0";
                            shots_table[i][3][0] = shots_table[i][3][1]+" of "+number_of_noise_pics; // number of noise pictures taken
                            shots_table[i][0][1] = "gray";
                            shots_table[i][1][1] = "gray";
                            shots_table[i][2][1] = "gray";

                            log(TAG,msg);
                        }
                    m_is_auto_pic = false;
                    }
                    else // not auto pic so we are in bracket loop
                    {
                        String nul ="";
                        shots_table[current_count][0][1] = "white";
                        if (current_count<10){nul ="0";}
                        if ( (current_count & 1) == 0 )
                        {
                            //even is min
                            extra = "i" + nul + Integer.toString(current_count) + "_m" + Integer.toString(Math.abs(bracket_array[current_count][2].intValue()));
                        }
                        else
                        {
                            //oneven is plus
                            extra = "i" + nul + Integer.toString(current_count) + "_p" + Integer.toString(bracket_array[current_count][2].intValue());
                        }
                        extra += "_c" + Integer.toString(noise_count);

                        shots_table[current_count][3][0] = (number_of_noise_pics - noise_count)+1+" of "+number_of_noise_pics; // number of noise pictures taken
                        if (noise_count == 1)
                        {
                            current_count++;
                            noise_count = number_of_noise_pics;
                        }
                        else
                        {
                            noise_count--;
                        }
                    }

                    //sort array from high to low
                    //Arrays.sort(bracket_array, (a, b) -> Double.compare(a[2], b[2]));

                    String opath = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" +  session_name + "/" + extra + ".jpg";
                    //String opath = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/IMG_" + Integer.toString(current_count) + ".JPG";

                    FileOutputStream fos;
                    fos = new FileOutputStream(opath);
                    fos.write(data);

                    ExifInterface exif = new ExifInterface(opath);
                    Float shutter_speed_float = Float.parseFloat(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME));
                    DecimalFormat df = new DecimalFormat("00.00000");
                    df.setMaximumFractionDigits(5);
                    String shutter_speed_string = df.format(shutter_speed_float);

                    //File fileold = new File(opath);
                    String opath_new = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" +
                    session_name + "/" + extra +
                    "_iso" +exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) +
                    "_shttr" + shutter_speed_string +
                    "s.jpg";
                    //File filenew = ;

                    if (!extra.contains("auto_pic")) // save filename for easy retrieve later on
                    {
                        filename_array.add(opath_new);
                    }
                    else
                    {   // this is the auto pic
                        auto_pic = opath_new;
                    }
                    // check for full white pic and replace that with default white jpg to save time
                    Log.d(TAG,"_c" + Integer.toString(number_of_noise_pics)+"_");

                    if (opath_new.contains("_c" + Integer.toString(number_of_noise_pics)+"_"))
                    {
                        log(TAG, "checking for full white");
                        temp_pic2 = new Mat();
                        temp_pic2 = imread(opath);
                        Scalar mean = org.opencv.core.Core.mean(temp_pic2);
                        temp_pic2.release();
                        double new_mean = (mean.val[0] + mean.val[1] + mean.val[2]) / 3.0;
                        //log(TAG, "Average Mean: " + Double.toString(new_mean));
                        if (new_mean == 255.0)
                        {
                            // We can skip these images and replace them with resource white jpg
                            // because they are full white
                            white_picture = opath_new;
                            saved_white_data = data;

                            for (int i=current_count; i < numberOfPictures;i=i+2)
                            {
                                bracket_array[i][4] = 0.0;
                                log(TAG, "no pic on: " + Double.toString(i));
                            }
                        }
                    }

                    new File(opath).renameTo(new File(opath_new));
                    log(TAG,"Saving file " + opath_new);

                    log(TAG,"Shot with iso " + exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) +" and a shutter of "+  shutter_speed_string + " sec.\n");
                    if(opath_new.contains("_c1_"))
                    {
                        new Thread(new average_thread()).start();
                    }

                    Log.d(TAG,"EXIF iso value: " + exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS));
                    //Log.d(TAG,"EXIF shutter value " + exif.getAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE) + " or " + out + " sec.");
                    Log.d(TAG,"EXIF shutter value/exposure value " + exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) + " sec.");
                    //Log.d(TAG,"EXIF Color Temp: " + exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE));
                    //Log.d(TAG,"EXIF white point: " + exif.getAttribute(ExifInterface.TAG_WHITE_POINT));


                    fos.close();

                    registImage(opath_new, mcontext);
                }
                catch (Exception e)
                {
                    log(TAG,"Begin big error.");
                    e.printStackTrace();
                    log(TAG,"End big error.");
                }

                if (auto_pic != "") // setting up auto picture for display in website
                {
                    //Log.i(TAG,"before jpg read.");
                    Mat t_pic = new Mat();
                    t_pic = imread(auto_pic);

                    Imgproc.resize(t_pic, t_pic, new Size(cols*0.15,rows*0.15),Imgproc.INTER_LINEAR);
                    compressParams_jpg = new MatOfInt(org.opencv.imgcodecs.Imgcodecs.IMWRITE_JPEG_QUALITY , 60);
                    String  new_name = auto_pic.substring(0,auto_pic.length()-4)+"_small.jpg";
                    //Log.i(TAG,"after jpg read."+new_name);
                    imwrite(new_name, t_pic,compressParams_jpg);
                    compressParams_jpg = new MatOfInt(org.opencv.imgcodecs.Imgcodecs.IMWRITE_JPEG_QUALITY , 100);
                    t_pic.release();
                    //Log.i(TAG,"after jpg read."+new_name);

                    InputStream inStream = null;
                    BufferedInputStream bis = null;
                    try
                    {
                        inStream = new FileInputStream(new_name);
                        bis = new BufferedInputStream(inStream);
                        byte[] imageBytes = new byte[0];
                        for (byte[] ba = new byte[bis.available()];
                             bis.read(ba) != -1; ) {
                            byte[] baTmp = new byte[imageBytes.length + ba.length];
                            System.arraycopy(imageBytes, 0, baTmp, 0, imageBytes.length);
                            System.arraycopy(ba, 0, baTmp, imageBytes.length, ba.length);
                            imageBytes = baTmp;
                        }
                        encodedImage = encodeArray(imageBytes);
                    }
                    catch(Exception e){ e.printStackTrace();}
                    finally
                    {   // releases any system resources associated with the stream
                        try
                        {
                            if (inStream != null)
                                inStream.close();
                            if (bis != null)
                                bis.close();
                        }
                        catch(Exception e){e.printStackTrace();}
                    }
                }

                //restart preview
                Camera.Parameters params = mCamera.getParameters();
                params.set("RIC_SHOOTING_MODE", "RicMonitoring");
                mCamera.setParameters(params);
                mCamera.startPreview();

                if(bcnt > 0 && !abort) // still taking pictures
                {
                    take_more_pictures();
                }
                else // Doing processing HDR merge etc
                {
                    if(MergeHDRI)
                    {
                        HDR_processing();
                    }
                    else
                    {
                        Log.i(TAG, "Skipped processing of HDR due to flag");
                        taking_pics = false;

                        if (sound)
                        {
                            sendBroadcast(new Intent("com.theta360.plugin.ACTION_AUDIO_SH_CLOSE"));
                        }
                    }
                }
                }
            }
    };
}

class Base64Coder {

    // The line separator string of the operating system.
    private static final String systemLineSeparator = System.getProperty("line.separator");

    // Mapping table from 6-bit nibbles to Base64 characters.
    private static final char[] map1 = new char[64];
    static {
        int i=0;
        for (char c='A'; c<='Z'; c++) map1[i++] = c;
        for (char c='a'; c<='z'; c++) map1[i++] = c;
        for (char c='0'; c<='9'; c++) map1[i++] = c;
        map1[i++] = '+'; map1[i++] = '/'; }

    // Mapping table from Base64 characters to 6-bit nibbles.
    private static final byte[] map2 = new byte[128];
    static {
        for (int i=0; i<map2.length; i++) map2[i] = -1;
        for (int i=0; i<64; i++) map2[map1[i]] = (byte)i; }

    /**
     * Encodes a string into Base64 format.
     * No blanks or line breaks are inserted.
     * @param s  A String to be encoded.
     * @return   A String containing the Base64 encoded data.
     */
    public static String encodeString (String s) {
        return new String(encode(s.getBytes())); }

    /**
     * Encodes a byte array into Base 64 format and breaks the output into lines of 76 characters.
     * This method is compatible with <code>sun.misc.BASE64Encoder.encodeBuffer(byte[])</code>.
     * @param in  An array containing the data bytes to be encoded.
     * @return    A String containing the Base64 encoded data, broken into lines.
     */
    public static String encodeLines (byte[] in) {
        return encodeLines(in, 0, in.length, 76, systemLineSeparator); }

    /**
     * Encodes a byte array into Base 64 format and breaks the output into lines.
     * @param in            An array containing the data bytes to be encoded.
     * @param iOff          Offset of the first byte in <code>in</code> to be processed.
     * @param iLen          Number of bytes to be processed in <code>in</code>, starting at <code>iOff</code>.
     * @param lineLen       Line length for the output data. Should be a multiple of 4.
     * @param lineSeparator The line separator to be used to separate the output lines.
     * @return              A String containing the Base64 encoded data, broken into lines.
     */
    public static String encodeLines (byte[] in, int iOff, int iLen, int lineLen, String lineSeparator) {
        int blockLen = (lineLen*3) / 4;
        if (blockLen <= 0) throw new IllegalArgumentException();
        int lines = (iLen+blockLen-1) / blockLen;
        int bufLen = ((iLen+2)/3)*4 + lines*lineSeparator.length();
        StringBuilder buf = new StringBuilder(bufLen);
        int ip = 0;
        while (ip < iLen) {
            int l = Math.min(iLen-ip, blockLen);
            buf.append(encode(in, iOff+ip, l));
            buf.append(lineSeparator);
            ip += l; }
        return buf.toString(); }

    /**
     * Encodes a byte array into Base64 format.
     * No blanks or line breaks are inserted in the output.
     * @param in  An array containing the data bytes to be encoded.
     * @return    A character array containing the Base64 encoded data.
     */
    public static char[] encode (byte[] in) {
        return encode(in, 0, in.length); }

    /**
     * Encodes a byte array into Base64 format.
     * No blanks or line breaks are inserted in the output.
     * @param in    An array containing the data bytes to be encoded.
     * @param iLen  Number of bytes to process in <code>in</code>.
     * @return      A character array containing the Base64 encoded data.
     */
    public static char[] encode (byte[] in, int iLen) {
        return encode(in, 0, iLen); }

    /**
     * Encodes a byte array into Base64 format.
     * No blanks or line breaks are inserted in the output.
     * @param in    An array containing the data bytes to be encoded.
     * @param iOff  Offset of the first byte in <code>in</code> to be processed.
     * @param iLen  Number of bytes to process in <code>in</code>, starting at <code>iOff</code>.
     * @return      A character array containing the Base64 encoded data.
     */
    public static char[] encode (byte[] in, int iOff, int iLen) {
        int oDataLen = (iLen*4+2)/3;       // output length without padding
        int oLen = ((iLen+2)/3)*4;         // output length including padding
        char[] out = new char[oLen];
        int ip = iOff;
        int iEnd = iOff + iLen;
        int op = 0;
        while (ip < iEnd) {
            int i0 = in[ip++] & 0xff;
            int i1 = ip < iEnd ? in[ip++] & 0xff : 0;
            int i2 = ip < iEnd ? in[ip++] & 0xff : 0;
            int o0 = i0 >>> 2;
            int o1 = ((i0 &   3) << 4) | (i1 >>> 4);
            int o2 = ((i1 & 0xf) << 2) | (i2 >>> 6);
            int o3 = i2 & 0x3F;
            out[op++] = map1[o0];
            out[op++] = map1[o1];
            out[op] = op < oDataLen ? map1[o2] : '='; op++;
            out[op] = op < oDataLen ? map1[o3] : '='; op++; }
        return out; }

    /**
     * Decodes a string from Base64 format.
     * No blanks or line breaks are allowed within the Base64 encoded input data.
     * @param s  A Base64 String to be decoded.
     * @return   A String containing the decoded data.
     * @throws   IllegalArgumentException If the input is not valid Base64 encoded data.
     */
    public static String decodeString (String s) {
        return new String(decode(s)); }

    /**
     * Decodes a byte array from Base64 format and ignores line separators, tabs and blanks.
     * CR, LF, Tab and Space characters are ignored in the input data.
     * This method is compatible with <code>sun.misc.BASE64Decoder.decodeBuffer(String)</code>.
     * @param s  A Base64 String to be decoded.
     * @return   An array containing the decoded data bytes.
     * @throws   IllegalArgumentException If the input is not valid Base64 encoded data.
     */
    public static byte[] decodeLines (String s) {
        char[] buf = new char[s.length()];
        int p = 0;
        for (int ip = 0; ip < s.length(); ip++) {
            char c = s.charAt(ip);
            if (c != ' ' && c != '\r' && c != '\n' && c != '\t')
                buf[p++] = c; }
        return decode(buf, 0, p); }

    /**
     * Decodes a byte array from Base64 format.
     * No blanks or line breaks are allowed within the Base64 encoded input data.
     * @param s  A Base64 String to be decoded.
     * @return   An array containing the decoded data bytes.
     * @throws   IllegalArgumentException If the input is not valid Base64 encoded data.
     */
    public static byte[] decode (String s) {
        return decode(s.toCharArray()); }

    /**
     * Decodes a byte array from Base64 format.
     * No blanks or line breaks are allowed within the Base64 encoded input data.
     * @param in  A character array containing the Base64 encoded data.
     * @return    An array containing the decoded data bytes.
     * @throws    IllegalArgumentException If the input is not valid Base64 encoded data.
     */
    public static byte[] decode (char[] in) {
        return decode(in, 0, in.length); }

    /**
     * Decodes a byte array from Base64 format.
     * No blanks or line breaks are allowed within the Base64 encoded input data.
     * @param in    A character array containing the Base64 encoded data.
     * @param iOff  Offset of the first character in <code>in</code> to be processed.
     * @param iLen  Number of characters to process in <code>in</code>, starting at <code>iOff</code>.
     * @return      An array containing the decoded data bytes.
     * @throws      IllegalArgumentException If the input is not valid Base64 encoded data.
     */
    public static byte[] decode (char[] in, int iOff, int iLen) {
        if (iLen%4 != 0) throw new IllegalArgumentException("Length of Base64 encoded input string is not a multiple of 4.");
        while (iLen > 0 && in[iOff+iLen-1] == '=') iLen--;
        int oLen = (iLen*3) / 4;
        byte[] out = new byte[oLen];
        int ip = iOff;
        int iEnd = iOff + iLen;
        int op = 0;
        while (ip < iEnd) {
            int i0 = in[ip++];
            int i1 = in[ip++];
            int i2 = ip < iEnd ? in[ip++] : 'A';
            int i3 = ip < iEnd ? in[ip++] : 'A';
            if (i0 > 127 || i1 > 127 || i2 > 127 || i3 > 127)
                throw new IllegalArgumentException("Illegal character in Base64 encoded data.");
            int b0 = map2[i0];
            int b1 = map2[i1];
            int b2 = map2[i2];
            int b3 = map2[i3];
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0)
                throw new IllegalArgumentException("Illegal character in Base64 encoded data.");
            int o0 = ( b0       <<2) | (b1>>>4);
            int o1 = ((b1 & 0xf)<<4) | (b2>>>2);
            int o2 = ((b2 &   3)<<6) |  b3;
            out[op++] = (byte)o0;
            if (op<oLen) out[op++] = (byte)o1;
            if (op<oLen) out[op++] = (byte)o2; }
        return out; }

    // Dummy constructor.
    private Base64Coder() {}

} // end class Base64Coder

