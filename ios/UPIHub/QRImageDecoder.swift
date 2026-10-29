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
