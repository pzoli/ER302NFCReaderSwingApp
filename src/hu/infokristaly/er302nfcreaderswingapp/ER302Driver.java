/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package hu.infokristaly.er302nfcreaderswingapp;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonGetter;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;

/**
 * Driver for Ehuoyan's YHY523U module
 *
 * @author pzoli
 */
public class ER302Driver {

    public static String parseNdefUri(byte[] data) {
        if (data.length < 5) return null;

        // data[0] = 0x03 (NDEF Tag)
        // data[1] = hossz
        // data[2] = 0xD1 (Header)
        // data[5] = Prefix (0x01, 0x02, 0x03, 0x04...)

        int prefixCode = data[6] & 0xFF;
        String prefix = "";
        switch (prefixCode) {
            case 0x01: prefix = "http://www."; break;
            case 0x02: prefix = "https://www."; break;
            case 0x03: prefix = "http://"; break;
            case 0x04: prefix = "https://"; break;
            default:   prefix = ""; break;
        }

        // A tényleges URL a 7. bájttól indul
        byte[] urlBytes = Arrays.copyOfRange(data, 7, data.length);
        return prefix + new String(urlBytes, Charset.forName("UTF-8"));
    }
    
    public static String decodeNdefText(byte[] raw) {
    try {
        // Keressük meg a 0x54 ('T') karaktert, ami a Text Record típusa
        int typeIndex = -1;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == 0x54) { 
                typeIndex = i;
                break;
            }
        }

        if (typeIndex == -1) return "Nem Text Record.";

        // A Payload a típusjelző ('T') után kezdődik
        int payloadStartIndex = typeIndex + 1;
        
        // Az első bájt a státuszbájt: 
        // Bit 7: UTF-8 (0) vagy UTF-16 (1)
        // Bit 5-0: A nyelvkód hossza (pl. "en" = 2)
        int statusByte = raw[payloadStartIndex] & 0xFF;
        int langCodeLength = statusByte & 0x3F; // Az alsó 6 bit
        
        // Kiszámoljuk, hol kezdődik a tényleges szöveg
        int textStartIndex = payloadStartIndex + 1 + langCodeLength;
        int textLength = raw.length - textStartIndex;

        if (textLength <= 0) return "";

        return new String(raw, textStartIndex, textLength, Charset.forName("UTF-8"));
    } catch (Exception e) {
        return "Hiba a dekódolás során: " + e.getMessage();
    }
}
    public static class CommandStruct {
        int id;
        byte[] cmd;
        String descrition;

        ReceivedStruct result;

        CommandStruct(int id, String descriptor, byte[] cmd) {
            this.id = id;
            this.descrition = descriptor;
            this.cmd = cmd;
        }
        
        public String getDescrition() {
            return descrition;
        }

        public void setDescrition(String descrition) {
            this.descrition = descrition;
        }

        public byte[] getCmd() {
            return cmd;
        }

        public void setCmd(byte[] cmd) {
            this.cmd = cmd;
        }
        public void setResult(ReceivedStruct result) {
            this.result = result;
        }

        public ReceivedStruct getResult() {
            return this.result;
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class ReceivedStruct {
        int length = 0;
        byte[] cmd;
        byte[] data;
        byte crc;
        boolean valid = false;
        byte error;
        List<String> log = new LinkedList<String>();

        private String getArrayAsHex(byte[] value) {
            if (value == null) return null;
            StringBuilder sb = new StringBuilder();
            for (byte b : value) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        }

        @JsonGetter("cmd") // A Jackson ezt fogja meghívni a JSON generálásakor
        public String getCmdAsHex() {
            return getArrayAsHex(cmd);
        }
        
        @JsonGetter("data") // A Jackson ezt fogja meghívni a JSON generálásakor
        public String getDataAsHex() {
            return getArrayAsHex(data);
        }

    }

    // Mifare types
    public static byte[] TYPE_MIFARE_UL = {0x44, 0x00};
    public static byte[] TYPE_MIFARE_1K = {0x04, 0x00};
    public static byte[] TYPE_MIFARE_4K = {0x02, 0x00};
    public static byte[] TYPE_MIFARE_DESFIRE = {0x44, 0x03};
    public static byte[] TYPE_MIFARE_PRO = {0x08, 0x00};

    // Command header
    public static byte[] HEADER = {(byte) 0xAA, (byte) 0xBB};
    // \x00\x00 according to API reference but only works with YHY632
    // \xFF\xFF works for both.
    public static byte[] RESERVED = {(byte) 0xFF, (byte) 0xFF};

    // Serial commands
    public static byte[] CMD_SET_BAUDRATE = {0x01, 0x01};
    public static byte[] CMD_SET_NODE_NUMBER = {0x02, 0x01};
    public static byte[] CMD_READ_NODE_NUMBER = {0x03, 0x01};
    public static byte[] CMD_READ_FW_VERSION = {0x04, 0x01};
    public static byte[] CMD_BEEP = {0x06, 0x01};
    public static byte[] CMD_LED = {0x07, 0x01};
    public static byte[] CMD_RFU = {0x08, 0x01}; // Unused according to API reference
    public static byte[] CMD_WORKING_STATUS = {0x08, 0x01}; // Unused according to API reference
    public static byte[] CMD_ANTENNA_POWER = {0x0C, 0x01};

    /*
     Request a type of card
     data = 0x52: request all Type A card In field,
     data = 0x26: request idle card
     */
    public static byte[] CMD_MIFARE_REQUEST = {0x01, 0x02};
    public static byte[] CMD_MIFARE_ANTICOLISION = {0x02, 0x02}; // 0x04 -> <NUL> (00)     [4cd90080]-cardnumber
    public static byte[] CMD_MIFARE_SELECT = {0x03, 0x02}; // [4cd90080] -> 0008
    public static byte[] CMD_MIFARE_HLTA = {0x04, 0x02};
    public static byte[] CMD_MIFARE_AUTH2 = {0x07, 0x02}; // 60[sector*4][key]
    public static byte[] CMD_MIFARE_READ_BLOCK = {0x08, 0x02}; //[block_number]
    public static byte[] CMD_MIFARE_WRITE_BLOCK = {0x09, 0x02};
    public static byte[] CMD_MIFARE_INITVAL = {0x0A, 0x02};
    public static byte[] CMD_MIFARE_READ_BALANCE = {0x0B, 0x02};
    public static byte[] CMD_MIFARE_DECREMENT = {0x0C, 0x02};
    public static byte[] CMD_MIFARE_INCREMENT = {0x0D, 0x02};
    public static byte[] CMD_MIFARE_UL_SELECT = {0x12, 0x02};
    public static byte[] CMD_MIFARE_UL_WRITE = {0x13, 0x02};

    // Default keys
    public static String[] DEFAULT_KEYS = {
        "000000000000",
        "a0a1a2a3a4a5",
        "b0b1b2b3b4b5",
        "4d3a99c351dd",
        "1a982c7e459a",
        "FFFFFFFFFFFF",
        "d3f7d3f7d3f7",
        "aabbccddeeff"
    };

    // Error codes
    public static int ERR_BAUD_RATE = 1;
    public static int ERR_PORT_OR_DISCONNECT = 2;
    public static int ERR_GENERAL = 10;
    public static int ERR_UNDEFINED = 11;
    public static int ERR_COMMAND_PARAMETER = 12;
    public static int ERR_NO_CARD = 13;
    public static int ERR_REQUEST_FAILURE = 20;
    public static int ERR_RESET_FAILURE = 21;
    public static int ERR_AUTHENTICATE_FAILURE = 22;
    public static int ERR_READ_BLOCK_FAILURE = 23;
    public static int ERR_WRITE_BLOCK_FAILURE = 24;
    public static int ERR_READ_ADDRESS_FAILURE = 25;
    public static int ERR_WRITE_ADDRESS_FAILURE = 26;

    public static byte[] hexStringToByteArray(String hexString) {
        byte[] bytes = HexFormat.of().parseHex(hexString);
        return bytes;
    }

    public static String byteArrayToHexString(byte[] buffer) {
        String result = HexFormat.of().formatHex(buffer).toUpperCase();
        return result;
    }

    public static byte[] intToByteArray(int input, boolean bigEndian) {
        byte[] result = ByteBuffer.allocate(4).putInt(input).array();
        return bigEndian ? result : new byte[]{result[3], result[2], result[1], result[0]};
    }

    public static byte[] shortToByteArray(short input, boolean bigEndian) {
        byte[] result = ByteBuffer.allocate(2).putShort(input).array();
        return bigEndian ? result : new byte[]{result[1], result[0]};
    }

    public static String build_command(String cmd, String data) {
        String result = null;
        return result;
    }

    public static byte crc(byte[] input) {
        int result = 0;
        for (int i = 0; i < input.length; i++) {
            result ^= input[i];
        }
        return (byte)result;
    }

    public static int byteArrayToInteger(byte[] src, boolean bigEndian) {
        ByteBuffer wrapped = ByteBuffer.wrap(bigEndian ? src : new byte[]{src[3], src[2], src[1], src[0]}); // big-endian by default
        int num = wrapped.getInt();
        return num;
    }

    public static short byteArrayToShort(byte[] src, boolean bigEndian) {
        ByteBuffer wrapped = ByteBuffer.wrap(bigEndian ? src : new byte[]{src[1], src[0]}); // big-endian by default
        short num = wrapped.getShort();
        return num;
    }

    public static ReceivedStruct decodeReceivedData(byte[] rc) throws IndexOutOfBoundsException {
        ReceivedStruct result = new ReceivedStruct();
        if (rc.length >= 4) {
            if (Arrays.equals(Arrays.copyOf(rc, 2), HEADER)) {
                result.log.add("Valid header.");
                short length = byteArrayToShort(Arrays.copyOfRange(rc, 2, 4), false);
                if ((length > 0) && (Arrays.equals(Arrays.copyOfRange(rc, 4, 6), RESERVED))) {
                    result.log.add("Valid reserved word.");
                    result.cmd = Arrays.copyOfRange(rc, 6, 8);
                    result.log.add("CMD:" + byteArrayToHexString(result.cmd));
                    result.error = rc[8];
                    result.data = Arrays.copyOfRange(rc, 9, length + 3);
                    result.length = 4 + length;
                    result.log.add("Received data:" + byteArrayToHexString(result.data));
                    if (result.error == 0x00) {
                        result.crc = rc[result.length - 1];
                        byte[] crcBase = Arrays.copyOfRange(rc, 4, length + 3);
                        byte crcCalc = crc(crcBase);
                        if (result.crc == crcCalc) {
                            result.log.add("Valid CRC code.");
                            result.valid = true;
                        } else {
                            result.log.add("Invalid CRC code!");
                        }
                    } else {
                        result.log.add("Error code: " + result.error);
                    }
                }
            }
        }
        return result;
    }

    public static byte[] createNdefUrlMessage(String url) {
        byte prefix;
        String cleanUrl = url;

        switch(url) {
            case String u when (url.startsWith("https://www.")) -> {
                cleanUrl = url.substring(12);
                prefix = 0x02;
            }
            case String u when (url.startsWith("http://www.")) -> {
                cleanUrl = url.substring(11);
                prefix = 0x01;
            }
            case String u when (url.startsWith("https://")) -> {
                cleanUrl = url.substring(8);
                prefix = 0x04;
            }
            case String u when (url.startsWith("http://")) -> {
                cleanUrl = url.substring(7);
                prefix = 0x03;
            }
            default -> {throw new IllegalArgumentException("Not a valid URL!");}
        }
        byte[] urlBytes = cleanUrl.getBytes(Charset.forName("UTF-8"));
        int payloadLen = urlBytes.length + 1; // +1 a prefix miatt

        // 2. NDEF keretezés
        byte[] ndef = new byte[payloadLen + 4];
        ndef[0] = (byte) 0xD1; // Record Header (MB=1, ME=1, SR=1, TNF=0x01)
        ndef[1] = 0x01;        // Type Length (1 bájt, azaz az "U")
        ndef[2] = (byte) payloadLen; // Payload Length
        ndef[3] = 0x55;        // Record Type: "U" (URI)
        ndef[4] = prefix;      // URI Prefix (https://)

        System.arraycopy(urlBytes, 0, ndef, 5, urlBytes.length);

        // 3. TLV (Tag-Length-Value) boríték (Az NFC kártyának kell)
        byte[] tlv = new byte[ndef.length + 3];
        tlv[0] = 0x03;                // T: NDEF Message tag
        tlv[1] = (byte) ndef.length;  // L: Message length
        System.arraycopy(ndef, 0, tlv, 2, ndef.length);
        tlv[tlv.length - 1] = (byte) 0xFE; // Terminator TLV

        return tlv;
    }
    
    public static byte[] createNdefTextMessage(String text) {
        byte[] langBytes = "en".getBytes(Charset.forName("US-ASCII"));
        byte[] textBytes = text.getBytes(Charset.forName("UTF-8"));
        int payloadLen = 1 + langBytes.length + textBytes.length;

        // 1. NDEF Record Header
        byte[] ndef = new byte[payloadLen + 4];
        ndef[0] = (byte) 0xD1; // MB=1, ME=1, SR=1, TNF=0x01
        ndef[1] = 0x01;        // Type Length ("T")
        ndef[2] = (byte) payloadLen; // Teljes Payload hossz
        ndef[3] = 0x54;        // Record Type: "T" (Text)

        // 2. Payload: Status bájt + Nyelv + Szöveg
        ndef[4] = (byte) langBytes.length; // Pl. 2 (en)
        System.arraycopy(langBytes, 0, ndef, 5, langBytes.length);
        System.arraycopy(textBytes, 0, ndef, 5 + langBytes.length, textBytes.length);

        // 3. TLV boríték (Tag-Length-Value)
        byte[] tlv = new byte[ndef.length + 3];
        tlv[0] = 0x03;                // NDEF Message tag
        tlv[1] = (byte) ndef.length;  // Hossz
        System.arraycopy(ndef, 0, tlv, 2, ndef.length);
        tlv[tlv.length - 1] = (byte) 0xFE; // Terminator

        return tlv;
    }
}
