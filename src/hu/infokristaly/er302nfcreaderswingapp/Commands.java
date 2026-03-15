/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package hu.infokristaly.er302nfcreaderswingapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author pzoli
 */
public class Commands {
    public enum LED {
        RED, BLUE, OFF
    };

    public static byte[] buildCommand(byte[] cmd, byte[] data) {
        byte[] result = {};
        short length = (short) (2 + 1 + 2 + data.length); //HEADER {0xaa, 0xbb} + {0x00, LENGTH_IN_BYTES} + RESERVER {0xff, 0xff} + DATALENGTH

        ByteArrayOutputStream bodyRaw = new ByteArrayOutputStream();
        ByteArrayOutputStream msgRaw = new ByteArrayOutputStream();
        try {
            bodyRaw.write(ER302Driver.RESERVED);
            bodyRaw.write(cmd);
            bodyRaw.write(data);
            byte crc = ER302Driver.crc(bodyRaw.toByteArray());
            bodyRaw.write(crc);

            msgRaw.write(ER302Driver.HEADER);
            msgRaw.write(ER302Driver.shortToByteArray(length, false));
            msgRaw.write(bodyRaw.toByteArray());

            result = msgRaw.toByteArray();
        } catch (IOException ex) {
            Logger.getLogger("Commands").log(Level.SEVERE, null, ex);
        }
        return result;
    }

    public static byte[] beep(byte msec) {
        byte[] data = {msec};
        byte[] result = buildCommand(ER302Driver.CMD_BEEP, data);
        return result;
    }

    public static byte[] led(LED color) {
        byte[] data;
        switch (color) {
            case OFF:
                data = new byte[]{0x00};
                break;
            case RED:
                data = new byte[]{0x02}; //changed for my device from 0x01
                break;
            case BLUE:
                data = new byte[]{0x01}; //changed for my device from 0x02
                break;
            default:
                data = new byte[]{0x03}; //both led on, but my device is red only
        }
        byte[] result = buildCommand(ER302Driver.CMD_LED, data);
        return result;
    }

    public static byte[] mifareRequest() {
        byte[] data = {0x52};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_REQUEST, data);
        return result;
    }

    public static byte[] readBalance(byte sector, byte block) {
        byte[] data = {(byte) (sector * 4 + block)};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_READ_BALANCE, data);
        return result;
    }

    public static byte[] auth2(byte sector, String keyString, boolean keyA) {
        //byte[] key = new byte[6];
        //Arrays.fill(key, (byte) 0xFF);
        byte[] key = ER302Driver.hexStringToByteArray(keyString);
        ByteBuffer bb = ByteBuffer.allocate(8);
        bb.put(keyA ? (byte) 0x60 : (byte) 0x61);
        bb.put(((byte) (sector * 4)));
        bb.put(key);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_AUTH2, data);
        return result;
    }

    public static byte[] mifareAnticolision() {
        byte[] data = {0x04};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_ANTICOLISION, data);
        return result;
    }

    public static byte[] readFirmware() {
        byte[] data = {};
        byte[] result = buildCommand(ER302Driver.CMD_READ_FW_VERSION, data);
        return result;
    }

    public static byte[] cmdHltA() {
        byte[] data = {};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_HLTA, data);
        return result;
    }

    public static byte[] mifareULSelect() {
        byte[] data = {};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_UL_SELECT, data);
        return result;
    }

    public static byte[] mifareULWrite(byte page, byte[] data) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(page);
        bb.put(data);
        byte[] input = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_UL_WRITE, input);
        return result;
    }

    public static byte[] mifareULRead(byte page) {
        ByteBuffer bb = ByteBuffer.allocate(1);
        bb.put(page);
        byte[] input = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_READ_BLOCK, input);
        return result;
    }

    public static byte[] mifareSelect(byte[] select) {
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_SELECT, select);
        return result;
    }

    public static byte[] incBalance(byte sector, byte block, int i) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(((byte) (sector * 4 + block)));
        byte[] intInc = ER302Driver.intToByteArray(i, false);
        bb.put(intInc);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_INCREMENT, data);
        return result;
    }

    public static byte[] decBalance(byte sector, byte block, int i) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(((byte) (sector * 4 + block)));
        byte[] intInc = ER302Driver.intToByteArray(i, false);
        bb.put(intInc);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_DECREMENT, data);
        return result;
    }

    public static byte[] initBalance(byte sector, byte block, int i) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(((byte) (sector * 4 + block)));
        byte[] intInc = ER302Driver.intToByteArray(i, false);
        bb.put(intInc);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_INITVAL, data);
        return result;
    }

    public static byte[] writeFourBytesToBlock(byte sector, byte block, byte[] dataBlock) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(((byte) (sector * 4 + block)));
        bb.put(dataBlock);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_WRITE_BLOCK, data);
        return result;
    }

    public static byte[] writeFullBlock(byte sector, byte block, byte[] dataBlock) {
        ByteBuffer bb = ByteBuffer.allocate(17);
        bb.put(((byte) (sector * 4 + block)));
        bb.put(dataBlock);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_WRITE_BLOCK, data);
        return result;
    }

    public static byte[] readBlock(byte sector, byte block) {
        byte[] data = {(byte) (sector * 4 + block)};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_READ_BLOCK, data);
        return result;
    }

    public static byte[] readULPage(byte page) {
        byte[] data = {page};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_READ_BLOCK, data);
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
    
    public static byte[] createNdefVCardMessage(String name, String phone, String email) {
        // 1. A vCard szöveg összeállítása (vCard 2.1 vagy 3.0 szabvány)
        String vcard = "BEGIN:VCARD\n" +
                       "VERSION:3.0\n" +
                       "FN:" + name + "\n" +
                       "TEL:" + phone + "\n" +
                       "EMAIL:" + email + "\n" +
                       "END:VCARD";

        byte[] vcardBytes = vcard.getBytes(Charset.forName("UTF-8"));
        byte[] typeBytes = "text/vcard".getBytes(Charset.forName("US-ASCII"));

        // 2. NDEF Record Header
        // MB=1, ME=1, SR=1, TNF=0x02
        byte[] ndef = new byte[vcardBytes.length + typeBytes.length + 3];
        ndef[0] = (byte) 0xD2; 
        ndef[1] = (byte) typeBytes.length; // "text/vcard" hossza
        ndef[2] = (byte) vcardBytes.length; // Payload hossza

        // Típus másolása
        System.arraycopy(typeBytes, 0, ndef, 3, typeBytes.length);
        // Payload (vCard szöveg) másolása
        System.arraycopy(vcardBytes, 0, ndef, 3 + typeBytes.length, vcardBytes.length);

        // 3. TLV boríték (Tag-Length-Value)
        byte[] tlv = new byte[ndef.length + 3];
        tlv[0] = 0x03;               // NDEF Tag
        tlv[1] = (byte) ndef.length; // Üzenet hossza
        System.arraycopy(ndef, 0, tlv, 2, ndef.length);
        tlv[tlv.length - 1] = (byte) 0xFE; // Terminator

        return tlv;
    }

    public static String decodeNdefVCard(byte[] toByteArray) {
        try {
            String rawString = new String(toByteArray, Charset.forName("US-ASCII"));
            int typeIndex = rawString.indexOf("text/vcard");

            if (typeIndex == -1) return "vCard record not found.";

            int vCardStartIndex = typeIndex + "text/vcard".length();

            byte[] vCardBytes = Arrays.copyOfRange(toByteArray, vCardStartIndex, toByteArray.length);
            String vCardContent = new String(vCardBytes, Charset.forName("UTF-8")).trim();

            return vCardContent;
        } catch (Exception e) {
            return "Error during vCard decoding: " + e.getMessage();
        }
    }
    
}
