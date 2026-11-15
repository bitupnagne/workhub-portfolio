package com.portfolio.worksystem.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * otpauth URI 등을 스캔 가능한 QR PNG(data URI)로 변환한다.
 * zxing(core)로 매트릭스만 만들고 PNG 렌더는 JDK 내장 ImageIO 로 한다(별도 모듈 불필요).
 * 결과는 data:image/png;base64 라 폐쇄망에서도 외부요청 없이 표시된다.
 * (deploy-system QrCodeService 와 동일 구현)
 */
@Service
public class QrCodeService {

    private static final int BLACK = 0x000000;
    private static final int WHITE = 0xFFFFFF;

    public String pngDataUri(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET, "UTF-8",
                EncodeHintType.MARGIN, 2
            );
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? BLACK : WHITE);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }
}
