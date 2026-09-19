import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/** 解码截图里的二维码，核对内容是 drawlots://v1?... 且与界面显示一致。 */
public class QrDecode {

    /** zxing core 里没有 BufferedImage 的 LuminanceSource（那在 javase 包里），自己写一个最小实现。 */
    static class ImageSource extends LuminanceSource {
        private final byte[] luminances;

        ImageSource(BufferedImage image, int left, int top, int width, int height) {
            super(width, height);
            luminances = new byte[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = image.getRGB(left + x, top + y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    luminances[y * width + x] = (byte) ((r * 306 + g * 601 + b * 117) >> 10);
                }
            }
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            byte[] out = row != null && row.length >= getWidth() ? row : new byte[getWidth()];
            System.arraycopy(luminances, y * getWidth(), out, 0, getWidth());
            return out;
        }

        @Override
        public byte[] getMatrix() {
            return luminances;
        }
    }

    public static void main(String[] args) throws Exception {
        BufferedImage image = ImageIO.read(new File(args[0]));
        System.out.println("image=" + image.getWidth() + "x" + image.getHeight());

        // 先整图找，找不到再按二维码大致区域裁一块重试
        String text = decode(image, 0, 0, image.getWidth(), image.getHeight());
        if (text == null && args.length > 4) {
            text = decode(image, Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        }
        System.out.println(text == null ? "DECODE FAILED" : "QR CONTENT: " + text);
    }

    private static String decode(BufferedImage image, int left, int top, int width, int height) {
        try {
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new ImageSource(image, left, top, width, height)));
            return new MultiFormatReader().decode(bitmap, hints).getText();
        } catch (Exception e) {
            return null;
        }
    }
}
