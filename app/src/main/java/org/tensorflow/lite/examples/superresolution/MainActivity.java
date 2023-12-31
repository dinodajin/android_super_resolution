/*
 * Copyright 2020 The TensorFlow Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.superresolution;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.WorkerThread;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import android.content.Intent;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.VideoView;

import com.bumptech.glide.Glide;

/** A super resolution class to generate super resolution images from low resolution images * */
public class MainActivity extends AppCompatActivity {
  static {
    System.loadLibrary("SuperResolution");
  }

  private static final String TAG = "SuperResolution";
  private static final String MODEL_NAME = "ESRGAN.tflite";
  private static final int LR_IMAGE_HEIGHT = 50;
  private static final int LR_IMAGE_WIDTH = 50;
  private static final int UPSCALE_FACTOR = 4;
  private static final int SR_IMAGE_HEIGHT = LR_IMAGE_HEIGHT * UPSCALE_FACTOR;
  private static final int SR_IMAGE_WIDTH = LR_IMAGE_WIDTH * UPSCALE_FACTOR;
  private static final String LR_IMG_1 = "lr-1.jpg";
  private static final String LR_IMG_2 = "lr-2.jpg";
  private static final String LR_IMG_3 = "lr-3.jpg";
  private static final String LR_IMG_4 = "lr-4.png";
  private static final String LR_IMG_5 = "lr-5.png";
  private static final String LR_IMG_6 = "lr-6.png";

  private MappedByteBuffer model;
  private long superResolutionNativeHandle = 0;
  private Bitmap selectedLRBitmap = null;
  private boolean useGPU = false;

  private ImageView lowResImageView1;
  private ImageView lowResImageView2;
  private ImageView lowResImageView3;
  private ImageView lowResImageView4;
  private ImageView lowResImageView5;
  private ImageView lowResImageView6;
  private TextView selectedImageTextView;
  private Switch gpuSwitch;


  private Button add_image;
  private VideoView testVideo;
  private ImageView thumbnailIv;

  private Button btn_picture;
  private ImageView imageView;
  private static final int REQUEST_VIDEO_CODE=101;
  private static final int REQUEST_PERMISSION_CODE=1;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    add_image = findViewById(R.id.add_button);
    testVideo = findViewById(R.id.testVideo);

    //업로드할 동영상 선택 (촬영 및 앨범에서 선택)
    add_image.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {

        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

          ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSION_CODE);
        }

        AlertDialog.Builder msgBuilder = new AlertDialog.Builder(MainActivity.this)
                .setTitle("업로드할 동영상 선택")
                .setPositiveButton("동영상 촬영", new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialogInterface, int i) {
                    Intent videoTakeIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);

                    if(videoTakeIntent.resolveActivity(getPackageManager()) != null) {
                      startActivityForResult(videoTakeIntent,REQUEST_VIDEO_CODE);
                    }
                  }
                })
                .setNeutralButton("앨범선택", new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialogInterface, int i) {
                    Intent intent = new Intent();
                    intent.setType("video/*");
                    intent.setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(intent, 0);

                    File fileFile = getFilesDir();
                    String getFile = fileFile.getPath();
                    System.out.println(fileFile);
                    System.out.println(getFile);
                  }
                })
                .setNegativeButton("취소", new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialogInterface, int i) {
                    Toast.makeText(MainActivity.this, "안 끔", Toast.LENGTH_SHORT).show();
                  }
                });
        AlertDialog msgDlg = msgBuilder.create();
        msgDlg.show();
      }
    });

    final Button superResolutionButton = findViewById(R.id.upsample_button);
    lowResImageView1 = findViewById(R.id.low_resolution_image_1);
    lowResImageView2 = findViewById(R.id.low_resolution_image_2);
    lowResImageView3 = findViewById(R.id.low_resolution_image_3);
    lowResImageView4 = findViewById(R.id.low_resolution_image_4);
    lowResImageView5 = findViewById(R.id.low_resolution_image_5);
    lowResImageView6 = findViewById(R.id.low_resolution_image_6);
    selectedImageTextView = findViewById(R.id.chosen_image_tv);
    gpuSwitch = findViewById(R.id.switch_use_gpu);

    ImageView[] lowResImageViews = {lowResImageView1, lowResImageView2, lowResImageView3, lowResImageView4, lowResImageView5, lowResImageView6};

    AssetManager assetManager = getAssets();
    try {
      InputStream inputStream1 = assetManager.open(LR_IMG_1);
      Bitmap bitmap1 = BitmapFactory.decodeStream(inputStream1);
      lowResImageView1.setImageBitmap(bitmap1);

      InputStream inputStream2 = assetManager.open(LR_IMG_2);
      Bitmap bitmap2 = BitmapFactory.decodeStream(inputStream2);
      lowResImageView2.setImageBitmap(bitmap2);

      InputStream inputStream3 = assetManager.open(LR_IMG_3);
      Bitmap bitmap3 = BitmapFactory.decodeStream(inputStream3);
      lowResImageView3.setImageBitmap(bitmap3);

      InputStream inputStream4 = assetManager.open(LR_IMG_4);
      Bitmap bitmap4 = BitmapFactory.decodeStream(inputStream4);
      lowResImageView4.setImageBitmap(bitmap4);

      InputStream inputStream5 = assetManager.open(LR_IMG_5);
      Bitmap bitmap5 = BitmapFactory.decodeStream(inputStream5);
      lowResImageView5.setImageBitmap(bitmap5);

      InputStream inputStream6 = assetManager.open(LR_IMG_6);
      Bitmap bitmap6 = BitmapFactory.decodeStream(inputStream6);
      lowResImageView6.setImageBitmap(bitmap6);
    } catch (IOException e) {
      Log.e(TAG, "Failed to open an low resolution image");
    }

    for (ImageView iv : lowResImageViews) {
      setLRImageViewListener(iv);
    }

    //UPSAMPLE 버튼 클릭
    superResolutionButton.setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                compareSuperResolution();
              }
            });
  }


  //super resolution
  private void compareSuperResolution() {
    if (selectedLRBitmap == null) {
      Toast.makeText(
                      getApplicationContext(),
                      "Please choose one low resolution image",
                      Toast.LENGTH_LONG)
              .show();
      return;
    }

    if (superResolutionNativeHandle == 0) {
      superResolutionNativeHandle = initTFLiteInterpreter(gpuSwitch.isChecked());
    } else if (useGPU != gpuSwitch.isChecked()) {
      // We need to reinitialize interpreter when execution hardware is changed
      deinit();
      superResolutionNativeHandle = initTFLiteInterpreter(gpuSwitch.isChecked());
    }
    useGPU = gpuSwitch.isChecked();
    if (superResolutionNativeHandle == 0) {
      showToast("TFLite interpreter failed to create!");
      return;
    }

    int[] lowResRGB = new int[LR_IMAGE_HEIGHT * LR_IMAGE_WIDTH];
    selectedLRBitmap.getPixels(
            lowResRGB, 0, LR_IMAGE_WIDTH, 0, 0, LR_IMAGE_WIDTH, LR_IMAGE_HEIGHT);

    final long startTime = SystemClock.uptimeMillis();
    int[] superResRGB = doSuperResolution(lowResRGB);
    final long processingTimeMs = SystemClock.uptimeMillis() - startTime;
    if (superResRGB == null) {
      showToast("Super resolution failed!");
      return;
    }

    final LinearLayout resultLayout = findViewById(R.id.result_layout);
    final ImageView superResolutionImageView = findViewById(R.id.super_resolution_image);
    final ImageView nativelyScaledImageView = findViewById(R.id.natively_scaled_image);
    final TextView superResolutionTextView = findViewById(R.id.super_resolution_tv);
    final TextView nativelyScaledImageTextView =
            findViewById(R.id.natively_scaled_image_tv);
    final TextView logTextView = findViewById(R.id.log_view);

    // Force refreshing the ImageView
    superResolutionImageView.setImageDrawable(null);
    Bitmap srImgBitmap =
            Bitmap.createBitmap(
                    superResRGB, SR_IMAGE_WIDTH, SR_IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
    superResolutionImageView.setImageBitmap(srImgBitmap);
    nativelyScaledImageView.setImageBitmap(selectedLRBitmap);
    resultLayout.setVisibility(View.VISIBLE);
    logTextView.setText("Inference time: " + processingTimeMs + "ms");
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    //카메라로 촬영한 비디오
    if (requestCode == REQUEST_VIDEO_CODE && resultCode == RESULT_OK) {
      Uri videoUri = data.getData();
      testVideo.setVideoURI(videoUri);
      createThumbnail(this, videoUri.toString());
      testVideo.start();
    }


    //앨범에서 선택한 비디오
    if (requestCode == 0) {
      if (resultCode == RESULT_OK) {
        Uri videoUri = data.getData();
        testVideo.setVideoURI(videoUri);
        createThumbnail(this, videoUri.toString());
        testVideo.start();
      }
    }
  }

  //이미지 크롭
  private Bitmap getCroppedBitmap(Bitmap bitmap) {
    int left = bitmap.getWidth() / 2 - 25;
    int top = bitmap.getHeight() / 2 - 25;
    return Bitmap.createBitmap(bitmap, left, top, 50, 50);
  }

  //동영상에서 프레임 추출
  private Bitmap createThumbnail(Context activity, String path) {
    MediaMetadataRetriever mediaMetadataRetriever = null;
    Bitmap bitmap = null;
    try {

        mediaMetadataRetriever = new MediaMetadataRetriever();
        mediaMetadataRetriever.setDataSource(activity, Uri.parse(path));

        String playtime = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        long timeInmillisec = Long.parseLong( playtime );
        long duration = timeInmillisec / 1000;
        long hours = duration / 3600;
        long minutes = (duration - hours * 3600) / 60;
        long seconds = duration - (hours * 3600 + minutes * 60);
      for (int i = 0; i < seconds; i++) {
        bitmap = mediaMetadataRetriever.getFrameAtTime(1000000 + i * 1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        long time = System.currentTimeMillis();
        saveBitmapToGallery(bitmap, String.valueOf(time));
      }
//      compareSuperResolution();

//      thumbnailIv.setImageBitmap(bitmap);
    }catch (Exception e) {
      e.printStackTrace();
    } finally {
      if(mediaMetadataRetriever != null) {
        mediaMetadataRetriever.release();
      }
    }
    return bitmap;
  }
  //권한 체크
  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    switch (requestCode) {
      case REQUEST_PERMISSION_CODE: {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

        } else {
          Toast.makeText(this, "WRITE_EXTERNAL_STORAGE 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show();
        }
      }
    }
  }
  //카메라로 촬영한 이미지 앨범에 저장
  private void saveBitmapToGallery(Bitmap bitmap, String fileName) {
    OutputStream outputStream;
    try {
      File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

      if (!directory.exists()) {
        directory.mkdirs();
      }

      File file = new File(directory, fileName + ".jpg");
      outputStream = new FileOutputStream(file);
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
      outputStream.flush();
      outputStream.close();

      Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
      Uri contentUri = Uri.fromFile(file);
      mediaScanIntent.setData(contentUri);
      this.sendBroadcast(mediaScanIntent);
    } catch (IOException e) {
      Log.e("@@@@@", "======> " + e.getMessage());
      e.printStackTrace();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    deinit();
  }

  private void setLRImageViewListener(ImageView iv) {
    iv.setOnTouchListener(
            new View.OnTouchListener() {
              @Override
              public boolean onTouch(View v, MotionEvent event) {
                if (v.equals(lowResImageView1)) {
                  selectedLRBitmap = ((BitmapDrawable) lowResImageView1.getDrawable()).getBitmap();
                  selectedImageTextView.setText(
                          "You are using low resolution image: 1 ("
                                  + getResources().getString(R.string.low_resolution_1)
                                  + ")");
                } else if (v.equals(lowResImageView2)) {
                  selectedLRBitmap = ((BitmapDrawable) lowResImageView2.getDrawable()).getBitmap();
                  selectedImageTextView.setText(
                          "You are using low resolution image: 2 ("
                                  + getResources().getString(R.string.low_resolution_2)
                                  + ")");
                } else if (v.equals(lowResImageView3)) {
                  selectedLRBitmap = ((BitmapDrawable) lowResImageView3.getDrawable()).getBitmap();
                  selectedImageTextView.setText(
                          "You are using low resolution image: 3 ("
                                  + getResources().getString(R.string.low_resolution_3)
                                  + ")");
                } else if (v.equals(lowResImageView4)) {
                  selectedLRBitmap = ((BitmapDrawable) lowResImageView4.getDrawable()).getBitmap();
                  selectedImageTextView.setText(
                          "You are using low resolution image: 4 ("
                                  + getResources().getString(R.string.low_resolution_4)
                                  + ")");
                } else if (v.equals(lowResImageView5)) {
                  selectedLRBitmap = ((BitmapDrawable) lowResImageView5.getDrawable()).getBitmap();
                  selectedImageTextView.setText(
                          "You are using low resolution image: 5 ("
                                  + getResources().getString(R.string.low_resolution_5)
                                  + ")");
                } else if (v.equals(lowResImageView6)) {
                  selectedLRBitmap = ((BitmapDrawable) lowResImageView6.getDrawable()).getBitmap();
                  selectedImageTextView.setText(
                          "You are using low resolution image: 6 ("
                                  + getResources().getString(R.string.low_resolution_6)
                                  + ")");
                }
                return false;
              }
            });
  }

  @WorkerThread
  public synchronized int[] doSuperResolution(int[] lowResRGB) {
    return superResolutionFromJNI(superResolutionNativeHandle, lowResRGB);
  }

  private MappedByteBuffer loadModelFile() throws IOException {
    try (AssetFileDescriptor fileDescriptor =
                 AssetsUtil.getAssetFileDescriptorOrCached(getApplicationContext(), MODEL_NAME);
         FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
      FileChannel fileChannel = inputStream.getChannel();
      long startOffset = fileDescriptor.getStartOffset();
      long declaredLength = fileDescriptor.getDeclaredLength();
      return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }
  }

  private void showToast(String str) {
    Toast.makeText(getApplicationContext(), str, Toast.LENGTH_LONG).show();
  }

  private long initTFLiteInterpreter(boolean useGPU) {
    try {
      model = loadModelFile();
    } catch (IOException e) {
      Log.e(TAG, "Fail to load model", e);
    }
    return initWithByteBufferFromJNI(model, useGPU);
  }

  private void deinit() {
    deinitFromJNI(superResolutionNativeHandle);
  }

  private native int[] superResolutionFromJNI(long superResolutionNativeHandle, int[] lowResRGB);

  private native long initWithByteBufferFromJNI(MappedByteBuffer modelBuffer, boolean useGPU);

  private native void deinitFromJNI(long superResolutionNativeHandle);
}