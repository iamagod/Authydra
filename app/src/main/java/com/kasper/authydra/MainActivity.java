/*
 * Based on Ichi Hirota's dual-fisheye plug-in for the THETA V.
 * Modified to use Shutter speed instead of exposure compensation
 * Added openCV support
 *
 *
 * curl -X POST http://192.168.1.1/osc/commands/execute --data '{"name": "camera._setPlugin","parameters": {"packageName": "com.kasper.Authydra","boot":true,"force":false}}' -H 'content-type: application/json'
 * curl -X POST 192.168.1.1/osc/commands/execute --data '{"name":"camera._listPlugins"}' -H 'content-type: application/json'
 *
 * TODO ideas
 * - export default python script to recreate hdri offline?
 * - support opencv 4
 * - fix black hole sun
 * - support Z1
 * - support tonemapped jpg in theta default app -> no idea why it doesn't work, maybe something with adding right exif data but maybe not.
 *
 *
 * TODO v2.1
 * - log counter of progress
 * - calibrate auto stop jumps
 * - better layout of webinterface
 * - turn sound on/off
 * - turn iso looping on/off
 * - name session
 * - viewer
 * - dng support
 * - disk space warnings
 */



//package com.theta360.pluginapplication;
package com.kasper.authydra;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.support.media.ExifInterface;
import android.view.ViewDebug;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;

import org.opencv.android.OpenCVLoader;

import static org.opencv.core.CvType.typeToString;
import static org.opencv.imgcodecs.Imgcodecs.imread;
import static org.opencv.imgcodecs.Imgcodecs.imwrite;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.String;

import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import com.samskivert.mustache.Mustache;

import org.opencv.core.MatOfInt;
import org.opencv.core.Scalar;



import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.LedColor;
import com.theta360.pluginlibrary.values.LedTarget;

import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;
import java.text.DecimalFormat;

import java.nio.channels.FileChannel;


import static java.lang.Thread.sleep;
import java.util.ArrayList;
import java.util.function.DoubleToIntFunction;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;

public class MainActivity extends PluginActivity implements SurfaceHolder.Callback {

    //#################################################################################################
    private int numberOfPictures = 11;    // number of pictures for the bracket          #
    private int number_of_noise_pics = 3; // number of pictures take for noise reduction #
    //#################################################################################################

    Double stop_jumps = 2.5;      // stops jump between each bracket has become dynamic            #
    String stopjump = "auto";

    private Camera mCamera = null;
    private Context mcontext;
    private WebServer webServer;
    private int bcnt = 0; //bracketing count

    Double[][] bracket_array = new Double[numberOfPictures][5];
    Mat times = new Mat(numberOfPictures,1,CvType.CV_32F);

    int current_count = 0;
    int noise_count = number_of_noise_pics;

    int cols = 5376;
    int rows = 2688;

    ArrayList<String> filename_array = new ArrayList<String>();
    ArrayList<String> images_filename_array = new ArrayList<String>();

    Boolean ColorThread =true;

    String auto_pic;
    byte[] saved_white_data;
    String white_picture ="";
    String session_name ="";
    List<Mat> images = new ArrayList<Mat>(numberOfPictures);

    Mat temp_pic = new Mat();
    Mat temp_pic2 = new Mat();
    Mat hdrDebevecY = new Mat();
    //Mat temp_pic3 = new Mat();
    //Mat average_pic_jpg = new Mat();

    Boolean taking_pics =false;
    String message_log ="";
    int pics_count =0;



    // Set exr file to half float --> smaller files
    private MatOfInt compressParams = new MatOfInt(org.opencv.imgcodecs.Imgcodecs.CV_IMWRITE_EXR_TYPE, org.opencv.imgcodecs.Imgcodecs.IMWRITE_EXR_TYPE_HALF);

    // true will start with bracket
    private boolean m_is_bracket = true;
    private boolean m_is_auto_pic = true;

    Double shutter_table[][] =
            {
                    {0.0,  1/25000.0}, {1.0, 1/20000.0}, {2.0,  1/16000.0}, {3.0,  1/12500.0},
                    {4.0,  1/10000.0}, {5.0, 1/8000.0},  {6.0,  1/6400.0},  {7.0,  1/5000.0},
                    {8.0,  1/4000.0},  {9.0, 1/3200.0},  {10.0,	1/2500.0},  {11.0, 1/2000.0},
                    {12.0, 1/1600.0}, {14.0, 1/1000.0},  {15.0,	1/800.0},   {16.0, 1/640.0},
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

    private static final String TAG = "MainActivity";

    static {
        if (OpenCVLoader.initDebug()) {
            Log.i(TAG,"OpenCV initialize success");
        } else {
            Log.i(TAG, "OpenCV initialize failed");
        }
    }

    private void copyWithChannels(File source, File target, boolean append) {
        //Log.i("Copying files with channels.");
        //ensureTargetDirectoryExists(target.getParentFile());
        FileChannel inChannel = null;
        FileChannel outChannel = null;
        FileInputStream inStream = null;
        FileOutputStream outStream = null;
        try{
            try {
                inStream = new FileInputStream(source);
                inChannel = inStream.getChannel();
                outStream = new  FileOutputStream(target, append);
                outChannel = outStream.getChannel();
                long bytesTransferred = 0;
                //defensive loop - there's usually only a single iteration :
                while(bytesTransferred < inChannel.size()){
                    bytesTransferred += inChannel.transferTo(0, inChannel.size(), outChannel);
                }
            }
            finally {
                //being defensive about closing all channels and streams
                if (inChannel != null) inChannel.close();
                if (outChannel != null) outChannel.close();
                if (inStream != null) inStream.close();
                if (outStream != null) outStream.close();
            }
        }
        catch (FileNotFoundException ex){
            Log.d(TAG,"File not found: " + ex);
        }
        catch (IOException ex){
            Log.d(TAG,"Error"+ex);
        }
    }

    void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    public boolean zipFileAtPath(String sourcePath, String toLocation) {
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


    private void zipSubFolder(ZipOutputStream out, File folder,
                              int basePathLength) throws IOException {

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


    public String getLastPathComponent(String filePath) {
        String[] segments = filePath.split("/");
        if (segments.length == 0)
            return "";
        String lastPathComponent = segments[segments.length - 1];
        return lastPathComponent;
    }


    private void makePicture()
    {
        // If on second run we need to reset everything.
        notificationLedBlink(LedTarget.LED3, LedColor.GREEN, 300);
        current_count = 0;
        m_is_auto_pic = true;
        taking_pics = true;
        message_log ="";
        times = new Mat(numberOfPictures,1,org.opencv.core.CvType.CV_32F);
        images = new ArrayList<Mat>(numberOfPictures);
        temp_pic = new Mat();
        temp_pic2 = new Mat();
        filename_array = new ArrayList<String>();
        images_filename_array = new ArrayList<String>();

        noise_count = number_of_noise_pics;

        //images_before_avg = new ArrayList<Mat>(numberOfPictures * number_of_noise_pics);


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
        try {
        this.webServer.start();
        } catch (IOException e) {
        e.printStackTrace();
        }

        SurfaceView preview = (SurfaceView)findViewById(R.id.preview_id);
        SurfaceHolder holder = preview.getHolder();
        holder.addCallback(this);
        setKeyCallback(new KeyCallback()
        {

        @Override
        public void onKeyDown(int keyCode, KeyEvent event) {
                if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
                // If on second run we need to reset everything.

                log("TAG","5 seconds delay to run away.");
                //5sec delay timer to run away
                try{
                sleep(5000);
                } catch (InterruptedException e) {
                //e.printStackTrace();
                Log.i(TAG,"Sleep error.");
                }
                makePicture();

                }
                else if(keyCode == KeyReceiver.KEYCODE_WLAN_ON_OFF){ // Old code
                notificationLedBlink(LedTarget.LED3, LedColor.MAGENTA, 2000);

                }
            }

        @Override
        public void onKeyUp(int keyCode, KeyEvent event) {
                /*
                 * You can control the LED of the camera.
                 * It is possible to change the way of lighting, the cycle of blinking, the color of light emission.
                 * Light emitting color can be changed only LED3.
                 */
                }

        @Override
        public void onKeyLongPress(int keyCode, KeyEvent event) {
                notificationError("theta debug: " + Integer.toString(keyCode) + " was pressed too long");
                }
            });

    }

@Override
public void onResume() {
        super.onResume();
        if(m_is_bracket){
        notificationLedBlink(LedTarget.LED3, LedColor.MAGENTA, 2000);
        notificationLedHide(LedTarget.LED3);
        notificationLedShow(LedTarget.LED3);
        notificationLed3Show(LedColor.MAGENTA);
        }
        else {
        notificationLedBlink(LedTarget.LED3, LedColor.CYAN, 2000);
        }
        }

public void onPause() {
        super.onPause();
        }

protected void onDestroy() {
        super.onDestroy();
        if (this.webServer != null) {
        this.webServer.stop();
        }
        }

@Override
public void surfaceCreated(SurfaceHolder holder) {

        Log.i(TAG,"Camera opened");
        //LoadText(R.raw.master_crc_kasper);

        Intent intent = new Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_CLOSE");
        sendBroadcast(intent);
        mCamera = Camera.open();
        try {

        mCamera.setPreviewDisplay(holder);
        } catch (IOException e) {

        //e.printStackTrace();
        Log.i(TAG,"Camera opening error.");
        }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

        mCamera.stopPreview();
        Camera.Parameters params = mCamera.getParameters();
        params.set("RIC_SHOOTING_MODE", "RicMonitoring");

        List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
        Camera.Size size = previewSizes.get(0);
        for(int i = 0; i < previewSizes.size(); i++) {
        size = previewSizes.get(i);
        Log.d(TAG,"preview size = " + size.width + "x" + size.height);
        }
        params.setPreviewSize(size.width, size.height);
        mCamera.setParameters(params);
        mCamera.startPreview();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {

        Log.d(TAG,"camera closed");
        notificationLedHide(LedTarget.LED3);
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
        Intent intent = new Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_OPEN");
        sendBroadcast(intent);
        }


        private class WebServer extends NanoHTTPD {

        private static final int PORT = 8888;
        private Context context;

        private static final String INDEX_TEMPLATE_FILE_NAME = "index_template.html";
        private static final String INDEX_OUTPUT_FILE_NAME = "index_out.html";
        private static final String HTML_SELECTOR_ID_COLOR = "color";
        private static final String HTML_SELECTOR_ID_BRACKET = "brackets";
        private static final String HTML_SELECTOR_ID_DENOISE = "denoise";

        public WebServer(Context context) {
        super(PORT);
        this.context = context;
        }


        @Override
        public Response serve(IHTTPSession session)
        {
            String uri = session.getUri();
            String msg = "<meta name=\"viewport\" content=\"width=device-width; initial-scale=1.0; maximum-scale=1.0;\">" +
                    "<html><body style='background-color:black;'><font color='white'>"+
                    "<h1>Welcome to Authydra.</h1>";

            File[] arrayfile;
            Log.i("web", "Uri is " + uri);



            try {
            session.parseBody(new HashMap<String, String>());
            } catch (ResponseException | IOException r) {
            r.printStackTrace();
            }


            Map<String, String> parms = session.getParms();
            for (String key : parms.keySet()) {
            Log.d("web", key + "=" + parms.get(key));
            }

            //msg += "<a href='/Open_rap'>Open Image</a><br><br>";
            msg += "<form action='/files'>" +
            "<input type='submit' value='Manage Files'></form>";
            msg += "<br>Please select your settings:<br><form action='/pic'>" +
            //"Number of Brackets    : <input type='number' name='brackets' value = '1' step='2' min='1' max='11'><br>" +
            "Number of bracket pictures    : <select name='brackets'>" +
            "<option value='1'>1</option>" +
            "<option value='2'>2</option>" +
            "<option value='3'>3</option>" +
            "<option value='4'>4</option>" +
            "<option value='5'>5</option>" +
            "<option value='6'>6</option>" +
            "<option value='7'>7</option>" +
            "<option value='8'>8</option>" +
            "<option value='9'>9</option>" +
            "<option value='10'>10</option>" +
            "<option value='11'>11</option>" +
            "<option selected='selected'>11</option>"+
            "</select><br>" +
            //"Number of denoise pics: <input type='number' name='denoise'  value = '1'  step='1' min='1' max='5' ><br>" +
            "Number of denoise pictures: <select name='denoise'>" +
            "<option value='1'>1</option>" +
            "<option value='2'>2</option>" +
            "<option value='3'>3</option>" +
            "<option value='4'>4</option>" +
            "<option value='5'>5</option>" +
            "<option selected='selected'>3</option>"+
            "</select><br>" +
            "Number stop jumps between brackets: <select name='stopjump'>" +
            "<option value='auto'>auto</option>" +
            "<option value='0.5'>0.5</option>" +
            "<option value='1.0'>1.0</option>" +
            "<option value='1.5'>1.5</option>" +
            "<option value='2.0'>2.0</option>" +
            "<option value='2.5'>2.5</option>" +
            "</select><br>" +
            "<input type='submit' value='Take picture'></form>";


            //msg += "<br><br><a href='/pic'> <input type='button' value='Click to take PICTURE'></a><br><br>";

            //msg += "<div id='submit_button_box'> <button id='submit_button' type='submit' name='action' value='send'>take picture</button></div>";
            /*if (uri.equals("/toZIP"))
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

            }*/
            if (uri.equals("/files"))
            {
                File[] contents = new File("/storage/emulated/0/DCIM/100RICOH/").listFiles();
                Arrays.sort(contents);
                Log.i("web","number of files found: "+contents.length);
                msg = "<meta name=\"viewport\" content=\"width=device-width; initial-scale=1.0; maximum-scale=1.0;\">"+
                        "<html><body style='background-color:black;'><font color='white'><h1>Manage Files</h1>";
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


                    msg += "<form action=\"http://192.168.1.1:8888/download="+f.getName()+"\" method=\"get\">" +
                    "  <button type=\"Download\">Download</button>" +
                    f.getName()+
                    "  <button type=\"Delete\" formaction=\"http://192.168.1.1:8888/delete="+f.getName()+"\">Delete</button>" + size_dir_text+
                    "</form>";
                }
                msg += "<br><a href='http://192.168.1.1:8888'> <input type='button' value='Return'>" +
                //msg +=  "<br><a href='http://192.168.1.1:8888/toZIP'> <input type='button' value='convert folders to ZIP'>" +
                        "</font></body></html>";
                return newFixedLengthResponse(msg );
            }

            else if (uri.contains("/delete="))
            {
                String name = uri.split("=")[1];
                Log.i("web","Selected file is of files found: "+name);
                msg =   "\"<html><body style='background-color:black;'><font color='white'>"+
                        "<h1>Delete " + name + "??</h1>"+
                "<a href='http://192.168.1.1:8888/delyes=" + name + "'> <input type='button' value='YES'>------" +
                "<a href='http://192.168.1.1:8888/files'> <input type='button' value='NO'>" +
                "</font></body></html>";

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
            }
            else
            {
                Log.i("web", "Taking picture. With brackets at " + parms.get("brackets") + " and denoise at " + parms.get("denoise"));
                numberOfPictures = Integer.parseInt(parms.get("brackets"));
                number_of_noise_pics = Integer.parseInt(parms.get("denoise"));
                stopjump = parms.get("stopjump");
            }
            if (!taking_pics)
            {
                makePicture();
                taking_pics = true;
                message_log = "";
                msg = "<html><body style='background-color:black;'><font color='white'><h1>Busy taking pictures</h1><br>" +
                "<form action='http://192.168.1.1:8888/refresh'> <input type='submit' value='Refresh'></form></font></body></html>";
                return newFixedLengthResponse(msg );
            }

                else
                {
                    Response r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
                    r.addHeader("Location", "http://192.168.1.1:8888/refresh");
                    return r;
                }
            }
            else if (uri.equals("/refresh") && taking_pics)
            {

                Log.i("web", "refresh taking pics is true");
                msg =   "<html><body style='background-color:black;'><font color='white'>" +
                        "<h1>Busy taking pictures</h1><br>" +
                        "<form action='http://192.168.1.1:8888/refresh'> <input type='submit' value='Refresh'></form>";
                msg += message_log + "</font></body></html>";
                return newFixedLengthResponse(msg);

            }
            else
            {
                return newFixedLengthResponse(msg + "</font></body></html>\n");
            }
        }
    }


    /*
            @Override
            public Response serve(IHTTPSession session) {
                Method method = session.getMethod();
                String uri = session.getUri();
                switch (method) {
                    case GET:
                        return this.serveFile(uri);
                    case POST:
                        Map<String, List<String>> parameters = this.parseBodyParameters(session);
                        this.updatePreferences(uri, parameters);
                        return this.serveFile(uri);
                    default:
                        return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain",
                                "Method [" + method + "] is not allowed.");
                }
            }

            private Response serveFile(String uri) {
                switch (uri) {
                    case "/":
                        return this.newHtmlResponse(this.generateIndexHtmlContext(), INDEX_TEMPLATE_FILE_NAME, INDEX_OUTPUT_FILE_NAME);
                    default:
                        return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "URI [" + uri + "] is not found.");
                }
            }

            private Response newHtmlResponse(Map<String, Object> data, String templateFileName, String outFileName) {
                AssetManager assetManager = context.getAssets();
                try(InputStreamReader template = new InputStreamReader(assetManager.open(templateFileName));
                    OutputStreamWriter output = new OutputStreamWriter(openFileOutput(outFileName, Context.MODE_PRIVATE))) {
                    Mustache.compiler().compile(template).execute(data, output);
                    return newChunkedResponse(Status.OK, "text/html", openFileInput(outFileName));
                } catch (IOException e) {
                    e.printStackTrace();
                    return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", e.getMessage());
                }
            }

            private Map<String, List<String>> parseBodyParameters(IHTTPSession session) {
                Map<String, String> tmpRequestFile = new HashMap<>();
                try {
                    session.parseBody(tmpRequestFile);
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ResponseException e) {
                    e.printStackTrace();
                }
                return session.getParameters();
            }

            private void updatePreferences(String uri, Map<String, List<String>> parameters) {
                if(parameters == null) return;
                //Log.d("VFX","URI is "+uri);
                switch (uri) {
                    case "/":
                        //this.updateLedColor(parameters);
                        this.updateBracket(parameters);
                        this.updateDenoise(parameters);
                        //new TakePictureTask(mTakePictureTaskCallback).execute();
                        makePicture();
                        if (!taking_pics)
                        {
                            makePicture();
    /*
                            while(taking_pics)
                            {
                                newFixedLengthResponse(Status.OK, "text/plain", Integer.toString(pics_count));
                                try{
                                    Thread.sleep(1000);
                                }catch(InterruptedException ex)
                                {
                                    Thread.currentThread().interrupt();
                                }
                            }*/
    /*
                        }

                        return;
                    default:
                        //Log.d("VFX","URI is "+uri);
                        return;
                }
            }



            private void updateBracket(Map<String, List<String>> parameters) {
                if (parameters.get(HTML_SELECTOR_ID_BRACKET) == null || parameters.get(HTML_SELECTOR_ID_BRACKET).isEmpty()) { return; }
                String bracket = parameters.get(HTML_SELECTOR_ID_BRACKET).get(0);
                Log.i("VFX", "received bracket parameter from web UI: " + bracket);
                numberOfPictures = Integer.parseInt(bracket);
            }

            private void updateDenoise(Map<String, List<String>> parameters) {
                if (parameters.get(HTML_SELECTOR_ID_DENOISE) == null || parameters.get(HTML_SELECTOR_ID_DENOISE).isEmpty()) { return; }
                String denoise = parameters.get(HTML_SELECTOR_ID_DENOISE).get(0);
                Log.i("VFX", "received denoise parameter from web UI: " + denoise);
                number_of_noise_pics = Integer.parseInt(denoise);
            }

            private Map<String, Object> generateIndexHtmlContext() {
                Map<String, Object> context = new HashMap<>();
                context.putAll(this.generateLedColorContext());
                return context;
            }

            private Map<String, Object> generateLedColorContext() {
                Map<String, Object> ledContext = new HashMap<>();
                LedColor ledColor = loadLedColor();
                switch (ledColor) {
                    case BLUE:
                        ledContext.put("isBlue", true);
                        break;
                    case RED:
                        ledContext.put("isRed", true);
                        break;
                    case WHITE:
                        ledContext.put("isWhite", true);
                        break;
                    default:
                        ledContext.put("isBlue", true);
                }
                return ledContext;
            }

        }*/


        private void customShutter()
        {
            Intent intent = new Intent("com.theta360.plugin.ACTION_AUDIO_SH_OPEN");
            sendBroadcast(intent);

            Camera.Parameters params = mCamera.getParameters();
            //Log.d("shooting mode", params.flatten());
            params.set("RIC_SHOOTING_MODE", "RicStillCaptureStd");

            //params.set("RIC_PROC_STITCHING", "RicNonStitching");
            //params.setPictureSize(5792, 2896); // no stiching

            params.setPictureFormat(ImageFormat.JPEG);
            params.set("jpeg-quality",100);
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

            intent = new Intent("com.theta360.plugin.ACTION_AUDIO_SHUTTER");
            sendBroadcast(intent);
            mCamera.takePicture(null,null, null, pictureListener);
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
                    log("avg","---> Working on image: "+filename_array.get(i * number_of_noise_pics + j));
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
                log("avg", "---> Saving Averaged file as " + opath + ".");
                //notificationLedBlink(LedTarget.LED3, LedColor.RED, 300);
                imwrite(opath, average_pic);
                average_pic.release();
                images_filename_array.add(opath);
            }
            log("avg","---> Done average on image "+Integer.toString(i+1));
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
        imwrite(opath, ldrDrago );
        ldrDrago.release();
        }
        private void nextShutter(){
        //restart preview
        Camera.Parameters params = mCamera.getParameters();
        params.set("RIC_SHOOTING_MODE", "RicMonitoring");
        mCamera.setParameters(params);
        mCamera.startPreview();

        //shutter speed based bracket
        if(bcnt > 0) {
        params = mCamera.getParameters();
        params.set("RIC_SHOOTING_MODE", "RicStillCaptureStd");
        //shutterSpeedValue = shutterSpeedValue + shutterSpeedSpacing;
        if (m_is_auto_pic) {
        // So here we take our first picture on full auto settings to get
        // proper lighting settings to use a our middle exposure value
        params.set("RIC_EXPOSURE_MODE", "RicAutoExposureP");
        } else {
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


        }

        bcnt = bcnt - 1;
        if (bracket_array[current_count][4] == 1.0)
        {
        mCamera.setParameters(params);
        Intent intent = new Intent("com.theta360.plugin.ACTION_AUDIO_SHUTTER");
        sendBroadcast(intent);
        mCamera.takePicture(null, null, null, pictureListener);
        }
        else
        {
        // full white going on
        log(TAG,"Full white picture copy.");
        pictureListener.onPictureTaken(saved_white_data,mCamera);

        }
        }

        else{
        //////////////////////////////////////////////////////////////////////////
        //                                                                      //
        //                          HDR MERGE                                   //
        //                                                                      //
        //////////////////////////////////////////////////////////////////////////

        log(TAG,"Done with picture taking, let's start with the HDR merge.");

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

        try {
        // While the BufferedReader readLine is not null
        Integer i =0;
        double[] data = new double[3];
        while ((readLine = br.readLine()) != null) {
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

        } catch (IOException e) {
        e.printStackTrace();
        }



        log(TAG,"Calibration done, start saving curves.");
        //notificationLedBlink(LedTarget.LED3, LedColor.BLUE, 300);

        try
        {
        // We save the Camera Curve to disk
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
        catch(IOException e)
        {
        log(TAG,"IO error");
        }
        //notificationLedBlink(LedTarget.LED3, LedColor.RED, 300);

        log(TAG,"Preping merge.");
        Mat hdrDebevec = new Mat();
        org.opencv.photo.MergeDebevec mergeDebevec = org.opencv.photo.Photo.createMergeDebevec();



        //Log.d(TAG,"starting align");
        //org.opencv.photo.AlignMTB align = org.opencv.photo.Photo.createAlignMTB();
        //align.process(images,images);
        if (number_of_noise_pics==1){
        images_filename_array = filename_array;
        }
        else {
        log(TAG, "Merging average pics for denoise.");
        //for (Integer i = 0; i < numberOfPictures; i++) {
        //    Average_pics(i);
        while (images_filename_array.size() != numberOfPictures)
        {
        log("avg","Number of average files not ready yet, we wait. Done "+images_filename_array.size() +" of "+ numberOfPictures);
        try
        {
        Thread.sleep(100);
        }
        catch(InterruptedException ex)
        {
        Thread.currentThread().interrupt();
        }
        }
        }
        //notificationLedBlink(LedTarget.LED3, LedColor.BLUE, 300);
        images = new ArrayList<Mat>(numberOfPictures);
        for (Integer i=0;i<numberOfPictures; i++)
        {
        String name = images_filename_array.get(i);
        Log.d(TAG,"Adding file "+ name);
        images.add(imread(name));
        }




        log(TAG,"Starting merge.");
        mergeDebevec.process(images, hdrDebevec, times, responseDebevec);

        // Start Saving HDR Files.

        // We divide by the mean value of the whole picture to get the exposure values with a proper range.
        // Multiplied by 2 to get average value around 0.5 and 1.0, this has a better starting point.

        Scalar mean =  org.opencv.core.Core.mean(hdrDebevec);
        Log.d(TAG,"Mean: " + mean.toString());
        double new_mean = (mean.val[0]*2 + mean.val[1]*2 +mean.val[2]*2 )/3.0;
        log(TAG,"Average Mean: " + Double.toString(new_mean));
        org.opencv.core.Core.divide(hdrDebevec,new Scalar(new_mean,new_mean,new_mean,0),hdrDebevec);


        log(TAG,"Doing White balance.");
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

        temp_pic = imread(auto_pic);

        log(TAG,"Going through all white pixels.");
        for (int i = low_value; i < high_value; i++)
        {
        Core.inRange(temp_pic,new Scalar(i,i,i),new Scalar(i+3,i+3,i+3),mask);
        Core.bitwise_or(mask_pic_w,mask_pic,mask_pic,mask);
        }


        temp_pic.release();
        org.opencv.imgproc.Imgproc.cvtColor(mask_pic, temp_pic, org.opencv.imgproc.Imgproc.COLOR_RGB2GRAY);

        Core.findNonZero(temp_pic,coord);

        temp_pic.release();
        mask.release();
        mask_pic.release();
        mask_pic_w.release();


        Mat avg = new Mat(1, 1, CvType.CV_32FC3, new Scalar(0.0, 0.0, 0.0));

        log(TAG,"Found "+Integer.toString(coord.rows())+" white pixels.");
        for (Integer j = 0; j < coord.rows(); j++)
        {
        org.opencv.core.Core.add(avg, new Scalar(   hdrDebevec.get((int)coord.get(j,0)[1], (int)coord.get(j,0)[0])[0],
        hdrDebevec.get((int)coord.get(j,0)[1], (int)coord.get(j,0)[0])[1],
        hdrDebevec.get((int)coord.get(j,0)[1], (int)coord.get(j,0)[0])[2],
        0.0),avg);
        }
        org.opencv.core.Core.divide((double)coord.rows(),avg,avg);

        Log.d(TAG,"Average of white pixels is: " + String.valueOf(avg.get(0,0)[0])
        + " " +String.valueOf(avg.get(0,0)[1])
        +" "+String.valueOf(avg.get(0,0)[2]));



        double Y = (0.2126 * avg.get(0,0)[2] + 0.7152 * avg.get(0,0)[1] + 0.0722 * avg.get(0,0)[0]);
        Scalar multY = new Scalar(Y/avg.get(0,0)[0], Y/avg.get(0,0)[1], Y/avg.get(0,0)[2], 0.0);

        Log.d(TAG,"Brightness value is: " + String.valueOf(Y));
        Log.d(TAG,"Multiplying by: " + multY.toString());


        org.opencv.core.Core.divide(hdrDebevec,multY,hdrDebevecY); // Why divide and not mult? works better don't understand.

        double B1 = hdrDebevec.get((int)coord.get(0,0)[1], (int)coord.get(0,0)[0])[0];
        double G1 = hdrDebevec.get((int)coord.get(0,0)[1], (int)coord.get(0,0)[0])[1];
        double R1 = hdrDebevec.get((int)coord.get(0,0)[1], (int)coord.get(0,0)[0])[2];
        Log.d(TAG,"Before: " + String.valueOf(B1) +" "+ String.valueOf(G1) +" "+ String.valueOf(R1));

        B1 = hdrDebevecY.get((int)coord.get(0,0)[1], (int)coord.get(0,0)[0])[0];
        G1 = hdrDebevecY.get((int)coord.get(0,0)[1], (int)coord.get(0,0)[0])[1];
        R1 = hdrDebevecY.get((int)coord.get(0,0)[1], (int)coord.get(0,0)[0])[2];
        Log.d(TAG,"After Y: " + String.valueOf(B1) +" "+ String.valueOf(G1) +" "+ String.valueOf(R1));

        B1 = hdrDebevec.get((int)coord.get(coord.rows()-1,0)[1], (int)coord.get(coord.rows()-1,0)[0])[0];
        G1 = hdrDebevec.get((int)coord.get(coord.rows()-1,0)[1], (int)coord.get(coord.rows()-1,0)[0])[1];
        R1 = hdrDebevec.get((int)coord.get(coord.rows()-1,0)[1], (int)coord.get(coord.rows()-1,0)[0])[2];
        Log.d(TAG,"Before end: " + String.valueOf(B1) +" "+ String.valueOf(G1) +" "+ String.valueOf(R1));

        B1 = hdrDebevecY.get((int)coord.get(coord.rows()-1,0)[1], (int)coord.get(coord.rows()-1,0)[0])[0];
        G1 = hdrDebevecY.get((int)coord.get(coord.rows()-1,0)[1], (int)coord.get(coord.rows()-1,0)[0])[1];
        R1 = hdrDebevecY.get((int)coord.get(coord.rows()-1,0)[1], (int)coord.get(coord.rows()-1,0)[0])[2];
        Log.d(TAG,"After Y end: " + String.valueOf(B1) +" "+ String.valueOf(G1) +" "+ String.valueOf(R1));

            /*
            opath = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" + session_name + "_nY.EXR";
            log(TAG,"Saving EXR file as " + opath + ".");
            notificationLedBlink(LedTarget.LED3, LedColor.RED, 300);
            imwrite(opath, hdrDebevec,compressParams);
            */

        new Thread(new tonemap_thread()).start();


        opath = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" + session_name + ".EXR";
        log(TAG,"Saving EXR Y file as " + opath + ".");
        //notificationLedBlink(LedTarget.LED3, LedColor.RED, 150);
        imwrite(opath, hdrDebevecY,compressParams);

        // We try the hack to copy the file with an jpg extension to make it accesable on windows
        //log(TAG,"Saving EXR as a jpg copy hack.");
        //File source = new File(opath);

        // 5/16/2019 change by theta360.guide community
//            File target = new File(opath+"_removethis.JPG");
//            copyWithChannels(source, target, false);

        //File from = new File(opath);
        //File to = new File(opath+"_removethis.JPG");
        //if(from.exists())
        //    from.renameTo(to);

        // end change by theta360.guide community






        registImage(opath, mcontext);

        zipFileAtPath(Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" + session_name,
        Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" + session_name+".ZIP");

        deleteDir(new File(Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" + session_name));
        //  need do some stuff with exif data to fix reading in app

            /*
            //Drawable drawable = getResources().getDrawable(android.R.drawable.ref);
            //Bitmap bitmap = BitmapFactory.decodeResource(getResources(),R.drawable.ref);
            String opath_exif = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/exif_file.JPG";

            File file = new File(opath_exif);

            try {
                InputStream is_jpg = getResources().openRawResource(R.raw.ref);;
                OutputStream os = new FileOutputStream(file);
                byte[] data = new byte[is_jpg.available()];
                is_jpg.read(data);
                os.write(data);
                is_jpg.close();
                os.close();
            } catch (IOException e) {
                // Unable to create file, likely because external storage is
                // not currently mounted.
                log("ExternalStorage", "Error writing " + file, e);
            }
            log(TAG,"Exif copy ");
            try
            {
                ExifInterface tone_mapped_Exif = new ExifInterface(opath);
                ExifInterface ref_exif =  new ExifInterface(opath_exif);
                tone_mapped_Exif = ref_exif;
                tone_mapped_Exif.saveAttributes();
            }
            catch (Exception e)
            {
                log(TAG,"Exif error.");
                e.printStackTrace();
                log(TAG,"end exif error.");
            }
            */

        log(TAG,"File saving done.");
        hdrDebevec.release();
        hdrDebevecY.release();
        coord.release();
        responseDebevec.release();

        log(TAG,"----- JOB DONE -----");
        taking_pics = false;

        ColorThread = false;
        notificationLedBlink(LedTarget.LED3, LedColor.MAGENTA, 2000);
        notificationLedHide(LedTarget.LED3);
        notificationLedShow(LedTarget.LED3);
        notificationLed3Show(LedColor.MAGENTA);

        Intent intent = new Intent("com.theta360.plugin.ACTION_AUDIO_SH_CLOSE");
        sendBroadcast(intent);
        }

        }
        private double find_closest_shutter(double shutter_in)
        {
        int i;
        for( i=0; i<shutter_table.length; i++){
        if (shutter_table[i][1] > shutter_in) {
        break;
        }
        }
        return shutter_table[i][0];
        }

        private Camera.PictureCallback pictureListener = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
        //save image to storage
        Log.d(TAG,"onpicturetaken called ok");
        if (data != null) {

        try {
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

        Float new_shutter = shutter * iso_flt/100*2;
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
            if (stopjump.equals("auto")) {
                if (new_shutter <= 0.02) {
                    stop_jumps = 2.0;
                }
                if (new_shutter <= 0.002) {
                    stop_jumps = 1.5;
                }
                if (new_shutter <= 0.001) {
                    stop_jumps = 1.0;
                }
                if (new_shutter <= 0.0002) {
                    stop_jumps = 0.5;
                }
            }
            else
            {
                stop_jumps = Double.parseDouble(stopjump);

            }
        log(TAG,"Stop jumps are set to ----> "+Double.toString(stop_jumps) + ".");

        // iso is always the lowest for now maybe alter we can implement a fast option with higher iso
        // bracket_array =
        // {{iso,shutter,bracketpos, shutter_length_real, go_ahead },{iso,shutter,bracketpos,shutter_length_real, go_ahead },{iso,shutter,bracketpos,shutter_length_real, go_ahead },....}
        // {{50, 1/50, 0},{50, 1/25, +1},{50,1/100,-1},{50,1/13,+2},....}
        // go_aherad is to turn of pictur takinfg when pict get full white or full black by default set 1, 0 means no pic
        for( int i=0; i<numberOfPictures; i++)
        {
        boolean reached_18 = false;
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
        int iso = 1;

        int j;
        for( j=1; j<shutter_table.length-1; j++){
        if (shutter_table[j][1] > corrected_shutter) {
        break;
        }
        }
        bracket_array[i][3] = shutter_table[j][1];
        times.put(i,0, shutter_table[j][1]);

        if ((corrected_shutter >= 1.0))
        {
        // If shutter value goes above 1 sec we increase iso unless we have reached highest iso already

        while (corrected_shutter >=1.0 && !( reached_18))
        {
        corrected_shutter = corrected_shutter/2.0;
        if (iso == 1) { iso =3; }
        else          { iso = iso + 3; }
        if (iso >=18)
        {
        iso=18;
        //if (reached_18) {corrected_shutter = corrected_shutter * 2.0;}
        reached_18 = true;

        }

        }
        }
        if ((reached_18) && (bracket_array[i-2][0] == 18))
        {
        // previous one was already at highest iso.
        bracket_array[i][0] = 18.0;
        bracket_array[i][1] = find_closest_shutter(corrected_shutter);

        }
        bracket_array[i][0] = Double.valueOf(iso);
        bracket_array[i][1] = find_closest_shutter(corrected_shutter);
        bracket_array[i][2] = Math.ceil(i/2.0);

        }
        log(TAG,"Array: index "+Integer.toString(i) +
        " iso #: "+Integer.toString(bracket_array[i][0].intValue())+
        " shutter #: "+Integer.toString(bracket_array[i][1].intValue())+
        " bracketpos : "+Integer.toString(bracket_array[i][2].intValue())+
        " real shutter length : "+Double.toString(bracket_array[i][3]));
        }
        m_is_auto_pic = false;
        }
        else // not auto pic so we are in bracket loop
        {
        String nul ="";
        if (current_count<10){nul ="0";}
        if ( (current_count & 1) == 0 ) {
        //even is min

        extra = "i" + nul + Integer.toString(current_count) + "_m" + Integer.toString(Math.abs(bracket_array[current_count][2].intValue()));
        }
        else
        {
        //oneven is plus
        extra = "i" + nul + Integer.toString(current_count) + "_p" + Integer.toString(bracket_array[current_count][2].intValue());
        }

        extra += "_c" + Integer.toString(noise_count);
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
/*
                    if (!extra.contains("auto_pic")) // setup opencv array for hdr merge
                            {
                                //log(TAG,"adding to whole: "+opath);
                                images_before_avg.add(imread(opath));
                            }
*/

        // firmware 3.00 doesn't support the tag shuuter_speed_value anymore
        // But this whole piece was just extra info so let's throw it out

                    /*
                    String shutter_str = exif.getAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE);
                    Float shutter_flt = (Float.parseFloat(shutter_str.split("/")[0]) / Float.parseFloat(shutter_str.split("/")[1]));
                    String out ="";
                    if ( shutter_flt>0 )
                    {
                        out = "1/"+Double.toString(Math.floor(Math.pow(2,shutter_flt)));
                    }
                    else
                    {
                        out = Double.toString(1.0/(Math.pow(2,shutter_flt)));
                    }
                    */

        //String shttr_str = exif.getAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE);
        //log(TAG,"shutter_float is" + shutter_flt);
        Float shutter_speed_float = Float.parseFloat(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME));
        DecimalFormat df = new DecimalFormat("00.00000");
        df.setMaximumFractionDigits(5);
        String shutter_speed_string = df.format(shutter_speed_float);

        //File fileold = new File(opath);
        String opath_new = Environment.getExternalStorageDirectory().getPath()+ "/DCIM/100RICOH/" +
        session_name + "/" + extra +
        "_iso" +exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) +
        "_shutter" + shutter_speed_string +
        "sec.jpg";
        //File filenew = ;

        if (!extra.contains("auto_pic")) // save filename for easy retrieve later on
        {
        filename_array.add(opath_new);
        }
        else
        {
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
        log(TAG, "Average Mean: " + Double.toString(new_mean));
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
        if(opath_new.contains("_c1_")){
        new Thread(new average_thread()).start();
        }

        Log.d(TAG,"EXIF iso value: " + exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS));
        //Log.d(TAG,"EXIF shutter value " + exif.getAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE) + " or " + out + " sec.");
        Log.d(TAG,"EXIF shutter value/exposure value " + exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) + " sec.");
        //Log.d(TAG,"EXIF Color Temp: " + exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE));
        //Log.d(TAG,"EXIF white point: " + exif.getAttribute(ExifInterface.TAG_WHITE_POINT));


        fos.close();
        //registImage(tname, opath, mcontext, "image/jpeg");
        } catch (Exception e) {
        log(TAG,"Begin big error.");
        e.printStackTrace();
        log(TAG,"End big error.");

        }

        nextShutter();
        }
        }
        };
        private static String getNowDate(){
        final DateFormat df = new SimpleDateFormat("HH_mm_ss");
        final Date date = new Date(System.currentTimeMillis());
        return df.format(date);
        }

        private static String getSessionName(){
        final DateFormat df = new SimpleDateFormat("MM-dd_HH-mm");
        final Date date = new Date(System.currentTimeMillis());
        return "HDR" + df.format(date) ;
        }

        private static void registImage( String Path,Context mcontext  )
        {
        File f = new File(Path);
        ContentValues values = new ContentValues();

        ContentResolver contentResolver = mcontext.getContentResolver();

        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.TITLE, f.getName());
        values.put("_data", Path);
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        }


        }



