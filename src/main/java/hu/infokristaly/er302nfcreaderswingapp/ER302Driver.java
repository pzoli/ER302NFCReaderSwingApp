/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package hu.infokristaly.er302nfcreaderswingapp;

import java.nio.ByteBuffer;
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

    public static class ReceivedStruct {
        int length = 0;
        byte[] cmd;
        byte[] data;
        byte crc;
        boolean valid = false;
        byte error;
        List<String> log = new LinkedList<String>();
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

}
