import CoreImage
import UIKit

/// Reads a QR code from a picture, e.g. a screenshot of a merchant's UPI QR.
enum QRImageDecoder {
    static func decode(_ image: UIImage) -> String? {
        guard let ciImage = CIImage(image: image) else { return nil }
        let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil,
                                  options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        return detector?.features(in: ciImage)
            .compactMap { ($0 as? CIQRCodeFeature)?.messageString }
            .first
    }
}

/// Draws QR text as an image for sharing ("QR Share and Pay"). The text is the
/// original QR, unchanged, so a signed merchant QR stays valid.
enum QRImageEncoder {
    static func image(for text: String, scale: CGFloat = 12) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        // A white quiet zone around the code, as printed QRs have.
        let size = CGSize(width: output.extent.width + 8 * scale, height: output.extent.height + 8 * scale)
        return UIGraphicsImageRenderer(size: size).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIImage(cgImage: cgImage).draw(at: CGPoint(x: 4 * scale, y: 4 * scale))
        }
    }
}
