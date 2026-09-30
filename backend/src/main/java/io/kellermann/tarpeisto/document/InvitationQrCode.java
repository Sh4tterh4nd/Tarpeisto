package io.kellermann.tarpeisto.document;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/** Creation-only QR; unlike asset labels this payload is a credential-exchange fragment URL. */
@Component
public class InvitationQrCode {
    public String dataUrl(String url) {
        try {
            var matrix = new MultiFormatWriter()
                    .encode(url, BarcodeFormat.QR_CODE, 384, 384, Map.of(EncodeHintType.MARGIN, 4));
            var image = new BufferedImage(384, 384, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 384; y++)
                for (int x = 0; x < 384; x++) image.setRGB(x, y, matrix.get(x, y) ? 0 : 0xffffff);
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException("Could not generate invitation QR.");
        }
    }
}
