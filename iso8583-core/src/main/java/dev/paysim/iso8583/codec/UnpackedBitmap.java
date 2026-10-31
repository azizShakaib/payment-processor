package dev.paysim.iso8583.codec;

import dev.paysim.iso8583.Bitmap;

/** A decoded bitmap plus the absolute offset of the first byte after it. */
public record UnpackedBitmap(Bitmap bitmap, int nextOffset) {
}
