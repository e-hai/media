package androidx.media3.demo.transformer;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class HardwareEncoderInfo {

  public static List<String> getSupportedVideoEncoders() {
    List<String> encoders = new ArrayList<>();
    MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);

    for (MediaCodecInfo codecInfo : mediaCodecList.getCodecInfos()) {
      if (codecInfo.isEncoder()) {
        String[] types = codecInfo.getSupportedTypes();
        for (String type : types) {
          if (type.startsWith("video/")) {
            encoders.add(codecInfo.getName() + " - " + type);

            // 获取详细能力信息
            MediaCodecInfo.CodecCapabilities capabilities =
                codecInfo.getCapabilitiesForType(type);
            MediaCodecInfo.VideoCapabilities videoCaps =
                capabilities.getVideoCapabilities();

            Log.d("Encoder", "Codec: " + codecInfo.getName());
            Log.d("Encoder", "MIME Type: " + type);
            Log.d("Encoder", "Width Range: " +
                videoCaps.getSupportedWidths());
            Log.d("Encoder", "Height Range: " +
                videoCaps.getSupportedHeights());
            Log.d("Encoder", "Bitrate Range: " +
                videoCaps.getBitrateRange());
            Log.d("Encoder", "Frame Rates: " +
                videoCaps.getSupportedFrameRates());
          }
        }
      }
    }
    return encoders;
  }
}
