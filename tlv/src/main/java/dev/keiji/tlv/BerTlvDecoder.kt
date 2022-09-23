/*
 * Copyright (C) 2022 ARIYAMA Keiji
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.keiji.tlv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InvalidObjectException
import java.io.StreamCorruptedException
import java.math.BigInteger

/**
 * BER-TLV Decoder.
 */
class BerTlvDecoder {
    companion object {
        private const val MASK_MSB_BITS = 0b100_00000
        private const val MASK_PC_BITS = 0b00_1_00000
        private const val MASK_TAG_BITS = 0b00_0_11111
        private const val MASK_TAG_FULL_BITS: Byte = 0b00_0_11111

        private const val PC_PRIMITIVE = 0b0
        private const val PC_CONSTRUCTED = 0b1

        private const val MAX_LENGTH_FILED_LENGTH = 126

        /**
         * An interface to receive event about detect TLV-item on InputStream.
         * The Callback is set with BerTlvDecoder.readFrom method.
         */
        interface Callback {

            /**
             * Called when a TLV-item has been detected.
             *
             * @param tag
             * @param value
             */
            fun onItemDetected(tag: ByteArray, value: ByteArray)

            /**
             * Called when a large TLV-item has been detected.
             *
             * Note: BER-TLV length can be up to 126 bytes.
             *
             * @param tag
             * @param length
             * @param inputStream
             */
            fun onLargeItemDetected(
                tag: ByteArray,
                length: BigInteger,
                inputStream: InputStream
            )
        }

        /**
         * Read TLV-item(s) from the InputStream.
         *
         * @param inputStream
         * @param callback Callback to receive event about detect TLV-item
         */
        fun readFrom(
            inputStream: InputStream,
            callback: Callback,
        ) {
            while (true) {
                val tag = readTag(inputStream)
                tag ?: break

                val length = readLength(inputStream)

                val isLargeItem = length.bitLength() > (Integer.SIZE - 1)
                if (!isLargeItem) {
                    val value = readValue(inputStream, length)
                    callback.onItemDetected(tag, value)
                } else {
                    callback.onLargeItemDetected(tag, length, inputStream)
                }
            }
        }

        internal fun readValue(
            inputStream: InputStream,
            length: BigInteger,
        ): ByteArray {
            val dataLength = length.toInt()
            val data = ByteArray(dataLength)
            var offset = 0

            while (true) {
                val readLength = inputStream.read(data, offset, (dataLength - offset))
                if (readLength < 0) {
                    throw StreamCorruptedException()
                } else if (readLength < (dataLength - offset)) {
                    offset += readLength
                } else {
                    return data
                }
            }
        }

        internal fun readTag(inputStream: InputStream): ByteArray? {
            val tagStream = ByteArrayOutputStream()

            while (true) {
                val b = inputStream.read()
                if (b < 0) {
                    return null
                }

                val tagBits = (b and MASK_TAG_BITS).toByte()
                val tagSize = tagStream.size()

                if (tagSize == 0) {
                    if (tagBits != MASK_TAG_FULL_BITS) {
                        return byteArrayOf(b.toByte())
                    }
                    tagStream.write(byteArrayOf(b.toByte()))
                } else {
                    tagStream.write(byteArrayOf(b.toByte()))
                    if (b and MASK_MSB_BITS == 0) {
                        return tagStream.toByteArray()
                    }
                }
            }
        }

        internal fun readLength(inputStream: InputStream): BigInteger {
            val b = inputStream.read()
            if (b < 0) {
                throw StreamCorruptedException()
            }

            val longDef = b and MASK_MSB_BITS != 0

            if (!longDef) {
                return BigInteger.valueOf(b.toLong())
            } else {
                val fieldLength = b xor MASK_MSB_BITS
                if (fieldLength > MAX_LENGTH_FILED_LENGTH) {
                    throw InvalidObjectException("Long Definite length must not be grater 126 bytes.")
                }

                val lengthBytes = ByteArray(fieldLength)
                var offset = 0

                while (true) {
                    val readLength = inputStream.read(lengthBytes, offset, (fieldLength - offset))
                    if (readLength < 0) {
                        throw StreamCorruptedException()
                    } else if (readLength < (fieldLength - offset)) {
                        offset += readLength
                    } else {
                        break
                    }
                }
                return BigInteger(+1, lengthBytes)
            }
        }
    }
}
