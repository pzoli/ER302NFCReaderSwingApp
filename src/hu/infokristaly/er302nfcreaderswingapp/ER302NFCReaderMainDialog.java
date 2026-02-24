/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JDialog.java to edit this template
 */
package hu.infokristaly.er302nfcreaderswingapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import hu.infokristaly.er302nfcreaderswingapp.ER302Driver.ReceivedStruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;
import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortException;
import jssc.SerialPortList;

/**
 *
 * @author pzoli
 */
public class ER302NFCReaderMainDialog extends javax.swing.JDialog implements jssc.SerialPortEventListener {
    
    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(ER302NFCReaderMainDialog.class.getName());
    private byte state = 0;
    private byte ulReadPageIdx = 4;
    private int ulReadIdx = 0;
    
    ByteArrayOutputStream rawData = new ByteArrayOutputStream();

    private Queue<ER302Driver.CommandStruct> commands = new LinkedList<ER302Driver.CommandStruct>();

    private enum LED {
        RED, BLUE, OFF
    };

    private enum PROCESS {
        MESSAGE_SEQUENCE, SINGLE_MESSAGE, URL_MESSAGE
    };

    private ER302Driver.CommandStruct lastCommand;

    private SerialPort serialPort;
    
    private ByteArrayOutputStream bout;

    private byte[] typeBytes;
    private byte[] cardSerialNo;
    
    private PROCESS commandsProcessor = PROCESS.MESSAGE_SEQUENCE;

    private void addCommand(ER302Driver.CommandStruct cmd) {
        commands.add(cmd);
    }

    private void log(String msg) {
        System.out.println(msg);
        if (msg != null) {
            logArea.append(msg + "\n");
        }
    }
    
    private byte[] buildCommand(byte[] cmd, byte[] data) {
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
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            log(ex.getMessage());
        }
        return result;
    }

    private byte[] beep(byte msec) {
        byte[] data = {msec};
        byte[] result = buildCommand(ER302Driver.CMD_BEEP, data);
        return result;
    }

    private byte[] led(LED color) {
        byte[] data;
        switch(color) {
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

    private byte[] mifareRequest() {
        byte[] data = {0x52};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_REQUEST, data);
        return result;
    }

    private byte[] readBalance(byte sector, byte block) {
        byte[] data = {(byte) (sector * 4 + block)};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_READ_BALANCE, data);
        return result;
    }

    private byte[] auth2(byte sector) {
        byte[] key = new byte[6];
        Arrays.fill(key, (byte) 0xFF);
        ByteBuffer bb = ByteBuffer.allocate(8);
        bb.put((byte) 0x60);
        bb.put(((byte) (sector * 4)));
        bb.put(key);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_AUTH2, data);
        log("auth command: "+ER302Driver.byteArrayToHexString(result));
        return result;
    }

    private byte[] mifareAnticolision() {
        byte[] data = {0x04};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_ANTICOLISION, data);
        return result;
    }

    private byte[] readFirmware() {
        byte[] data = {};
        byte[] result = buildCommand(ER302Driver.CMD_READ_FW_VERSION, data);
        return result;
    }

    private byte[] cmdHltA() {
        byte[] data = {};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_HLTA, data);
        return result;
    }

    private byte[] mifareULSelect() {
        byte[] data = {};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_UL_SELECT, data);
        return result;
    }

    private byte[] mifareULWrite(byte page, byte[] data) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(page);
        bb.put(data);
        byte[] input = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_UL_WRITE, input);
        return result;
    }

    private byte[] mifareULRead(byte page) {
        ByteBuffer bb = ByteBuffer.allocate(1);
        bb.put(page);
        byte[] input = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_READ_BLOCK, input);
        return result;
    }

    private byte[] mifareSelect(byte[] select) {
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_SELECT, select);
        return result;
    }

    private byte[] incBalance(byte sector, byte block, int i) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(((byte) (sector * 4 + block)));
        byte[] intInc = ER302Driver.intToByteArray(i, false);
        bb.put(intInc);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_INCREMENT, data);
        return result;
    }

    private byte[] decBalance(byte sector, byte block, int i) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(((byte) (sector * 4 + block)));
        byte[] intInc = ER302Driver.intToByteArray(i, false);
        bb.put(intInc);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_DECREMENT, data);
        return result;
    }

    private byte[] initBalance(byte sector, byte block, int i) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(((byte) (sector * 4 + block)));
        byte[] intInc = ER302Driver.intToByteArray(i, false);
        bb.put(intInc);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_INITVAL, data);
        return result;
    }

    private byte[] writeBlock(byte sector, byte block, byte[] dataBlock) {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(((byte) (sector * 4 + block)));
        bb.put(dataBlock);
        byte[] data = bb.array();
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_WRITE_BLOCK, data);
        return result;
    }

    private byte[] readBlock(byte sector, byte block) {
        byte[] data = {(byte) (sector * 4 + block)};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_READ_BLOCK, data);
        return result;
    }

    private byte[] readULPage(byte page) {
        byte[] data = {page};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_READ_BLOCK, data);
        return result;
    }

    /**
     * Creates new form ER302NFCReaderMainDialog
     */
    public ER302NFCReaderMainDialog(java.awt.Frame parent, boolean modal) {
        super(parent, modal);
        initComponents();
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jLabel2 = new javax.swing.JLabel();
        serialPortList = new javax.swing.JComboBox<>();
        connectButton = new javax.swing.JButton();
        jScrollPane1 = new javax.swing.JScrollPane();
        logArea = new javax.swing.JTextArea();
        btnBeep = new javax.swing.JButton();
        btnSendMessageSequence = new javax.swing.JButton();
        btnSendSingleMessage = new javax.swing.JButton();
        txtHexString = new javax.swing.JTextField();
        jLabel1 = new javax.swing.JLabel();
        lblDecode = new javax.swing.JLabel();
        txtDecode = new javax.swing.JTextField();
        btnDecode = new javax.swing.JButton();
        btnClear = new javax.swing.JButton();
        txtCmd = new javax.swing.JTextField();
        txtParams = new javax.swing.JTextField();
        btnEncode = new javax.swing.JButton();
        jLabel3 = new javax.swing.JLabel();
        jLabel4 = new javax.swing.JLabel();
        txtURL = new javax.swing.JTextField();
        btnUploadURL = new javax.swing.JButton();
        jLabel5 = new javax.swing.JLabel();
        jLabel6 = new javax.swing.JLabel();
        txtURLDownload = new javax.swing.JTextField();
        jLabel7 = new javax.swing.JLabel();
        btnDownloadURL = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);

        jLabel2.setText("Port:");

        connectButton.setText("Connect");
        connectButton.addActionListener(this::connectButtonActionPerformed);

        logArea.setColumns(20);
        logArea.setRows(5);
        jScrollPane1.setViewportView(logArea);

        btnBeep.setText("Beep");
        btnBeep.addActionListener(this::btnBeepActionPerformed);

        btnSendMessageSequence.setText("Send message sequence");
        btnSendMessageSequence.addActionListener(this::btnSendMessageSequenceActionPerformed);

        btnSendSingleMessage.setText("Send");
        btnSendSingleMessage.addActionListener(this::btnSendSingleMessageActionPerformed);

        jLabel1.setText("Hexa String:");

        lblDecode.setText("Message:");

        btnDecode.setText("Decode");
        btnDecode.addActionListener(this::btnDecodeActionPerformed);

        btnClear.setText("Clear");
        btnClear.addActionListener(this::btnClearActionPerformed);

        btnEncode.setText("Encode");
        btnEncode.addActionListener(this::btnEncodeActionPerformed);

        jLabel3.setText("Command:");

        jLabel4.setText("Params:");

        btnUploadURL.setText("Upload");
        btnUploadURL.addActionListener(this::btnUploadURLActionPerformed);

        jLabel5.setText("URL to Ultralight:");

        jLabel6.setText("v0.2");

        jLabel7.setText("URL from Ultralight:");

        btnDownloadURL.setText("Download");
        btnDownloadURL.addActionListener(this::btnDownloadURLActionPerformed);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jLabel6, javax.swing.GroupLayout.PREFERRED_SIZE, 37, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addGap(23, 23, 23)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(jScrollPane1)
                    .addGroup(layout.createSequentialGroup()
                        .addGap(10, 10, 10)
                        .addComponent(jLabel2)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(serialPortList, javax.swing.GroupLayout.PREFERRED_SIZE, 146, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(connectButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(layout.createSequentialGroup()
                                        .addGap(12, 12, 12)
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                                            .addComponent(jLabel1)
                                            .addComponent(lblDecode)
                                            .addComponent(jLabel3)
                                            .addComponent(jLabel5))
                                        .addGap(3, 3, 3))
                                    .addComponent(jLabel7, javax.swing.GroupLayout.Alignment.TRAILING))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(txtURLDownload)
                                    .addComponent(txtHexString)
                                    .addComponent(txtURL)
                                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                                        .addComponent(txtCmd, javax.swing.GroupLayout.DEFAULT_SIZE, 83, Short.MAX_VALUE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(jLabel4, javax.swing.GroupLayout.PREFERRED_SIZE, 51, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(txtParams, javax.swing.GroupLayout.PREFERRED_SIZE, 90, javax.swing.GroupLayout.PREFERRED_SIZE))
                                    .addComponent(txtDecode, javax.swing.GroupLayout.Alignment.TRAILING)))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(btnBeep)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnSendMessageSequence, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(btnDownloadURL, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnSendSingleMessage, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnDecode, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnClear, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnEncode, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnUploadURL, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
                .addGap(30, 30, 30))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(serialPortList, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel2)
                    .addComponent(connectButton))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 213, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnBeep)
                    .addComponent(btnSendMessageSequence)
                    .addComponent(btnClear))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtHexString, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnSendSingleMessage)
                    .addComponent(jLabel1))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblDecode)
                    .addComponent(txtDecode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnDecode))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtCmd, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtParams, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnEncode)
                    .addComponent(jLabel3)
                    .addComponent(jLabel4))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtURL, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnUploadURL)
                    .addComponent(jLabel5))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtURLDownload, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnDownloadURL)
                    .addComponent(jLabel7))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jLabel6)
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void connectButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_connectButtonActionPerformed
        if (serialPortList.getSelectedItem() != null) {
            if (serialPort == null) {
                serialPort = new SerialPort(serialPortList.getSelectedItem().toString());
            }
            if (!serialPort.isOpened()) {
                try {
                    //Open port
                    serialPort.openPort();
                    //We expose the settings. You can also use this line - serialPort.setParams(9600, 8, 1, 0);
                    serialPort.setParams(SerialPort.BAUDRATE_115200,
                        SerialPort.DATABITS_8,
                        SerialPort.STOPBITS_1,
                        SerialPort.PARITY_NONE);
                    int mask = SerialPort.MASK_RXCHAR;
                    serialPort.setEventsMask(mask);
                    serialPort.addEventListener(this);
                    setState();
                    bout = new ByteArrayOutputStream();
                    log("Connected.");
                } catch (SerialPortException ex) {
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                    log(ex.getMessage());
                    setState();
                }
            } else {
                try {
                    serialPort.closePort();
                    setState();
                } catch (SerialPortException ex) {
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                    log(ex.getMessage());
                    setState();
                }
            }
        }
    }//GEN-LAST:event_connectButtonActionPerformed

    private void btnBeepActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnBeepActionPerformed
        commandsProcessor = PROCESS.SINGLE_MESSAGE;
        byte[] beepMsg = beep((byte) 100);
        try {
            serialPort.writeBytes(beepMsg);
        } catch (SerialPortException ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
            log(ex.getMessage());
        }

    }//GEN-LAST:event_btnBeepActionPerformed

    private void btnSendMessageSequenceActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendMessageSequenceActionPerformed
        commandsProcessor = PROCESS.MESSAGE_SEQUENCE;
        if (serialPort != null) {
            try {
                logArea.setText("");
                state = 0;
                byte[] beepMsg = beep((byte) 50);
                lastCommand = new ER302Driver.CommandStruct(0, "Beep", beepMsg);

                addCommand(new ER302Driver.CommandStruct(1, "Firmware version", readFirmware()));
                addCommand(new ER302Driver.CommandStruct(2, "MiFare request", mifareRequest()));

                log("Beep message: "+ER302Driver.byteArrayToHexString(beepMsg));
                serialPort.writeBytes(beepMsg);
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnSendMessageSequenceActionPerformed

    private void btnSendSingleMessageActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendSingleMessageActionPerformed
        commandsProcessor = PROCESS.SINGLE_MESSAGE;
        if (serialPort != null) {
            try {
                logArea.setText("");
                state = 0;
                txtHexString.setText(txtHexString.getText().replaceAll(" ", ""));
                byte[] msg = ER302Driver.hexStringToByteArray(txtHexString.getText());
                if (serialPort.writeBytes(msg)) {
                    log("Sent string: "+ER302Driver.byteArrayToHexString(msg));
                }
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnSendSingleMessageActionPerformed

    private void btnDecodeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDecodeActionPerformed
        ReceivedStruct struct = ER302Driver.decodeReceivedData(ER302Driver.hexStringToByteArray(txtDecode.getText()));
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            String json = mapper.writeValueAsString(struct);
            log("Received struct:" + json);
        } catch (JsonProcessingException ex) {
            System.getLogger(ER302NFCReaderMainDialog.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }//GEN-LAST:event_btnDecodeActionPerformed

    private void btnClearActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearActionPerformed
        logArea.setText("");
    }//GEN-LAST:event_btnClearActionPerformed

    private void btnEncodeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnEncodeActionPerformed
        txtHexString.setText(ER302Driver.byteArrayToHexString(buildCommand(ER302Driver.hexStringToByteArray(txtCmd.getText()), ER302Driver.hexStringToByteArray(txtParams.getText()))));
    }//GEN-LAST:event_btnEncodeActionPerformed

    public void writeUrlToTag(String url) throws SerialPortException, InterruptedException, IllegalArgumentException {
        commandsProcessor = PROCESS.SINGLE_MESSAGE;
        serialPort.writeBytes(mifareRequest());
        Thread.sleep(Duration.ofMillis(100));
        serialPort.writeBytes(mifareAnticolision());
        Thread.sleep(Duration.ofMillis(100));
        serialPort.writeBytes(mifareULSelect());
        Thread.sleep(Duration.ofMillis(100));
        byte[] dataToWrite = ER302Driver.createNdefUrlMessage(url);

        for (int i = 0; i < dataToWrite.length; i += 4) {
            byte[] chunk = new byte[4];
            int remaining = dataToWrite.length - i;
            System.arraycopy(dataToWrite, i, chunk, 0, Math.min(4, remaining));

            int page = 4 + (i / 4);
            byte[] pcmd = mifareULWrite((byte)page, chunk);
            serialPort.writeBytes(pcmd);
            Thread.sleep(Duration.ofMillis(100));
            log("Writing page " + page + ": " + ER302Driver.byteArrayToHexString(chunk));
        }
        serialPort.writeBytes(cmdHltA());
    }
    
    private void btnUploadURLActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUploadURLActionPerformed
        try {
            writeUrlToTag(txtURL.getText().trim());
        } catch (SerialPortException | IllegalArgumentException | InterruptedException ex) {
            System.getLogger(ER302NFCReaderMainDialog.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }//GEN-LAST:event_btnUploadURLActionPerformed

    private void btnDownloadURLActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDownloadURLActionPerformed
        commandsProcessor = PROCESS.URL_MESSAGE;
        ulReadPageIdx = 4;
        rawData = new ByteArrayOutputStream();
        if (serialPort != null) {
            try {
                logArea.setText("");
                byte[] beepMsg = beep((byte) 50);
                lastCommand = new ER302Driver.CommandStruct(0, "Beep", beepMsg);

                addCommand(new ER302Driver.CommandStruct(1, "Firmware version", readFirmware()));
                addCommand(new ER302Driver.CommandStruct(2, "MiFare request", mifareRequest()));

                log("Beep message: "+ER302Driver.byteArrayToHexString(beepMsg));
                serialPort.writeBytes(beepMsg);
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnDownloadURLActionPerformed

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ex) {
            logger.log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the dialog */
        java.awt.EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                ER302NFCReaderMainDialog dialog = new ER302NFCReaderMainDialog(new javax.swing.JFrame(), true);
                dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        System.exit(0);
                    }
                });
                String[] portNames = SerialPortList.getPortNames();
                for (int i = 0; i < portNames.length; i++) {
                    dialog.serialPortList.addItem(portNames[i]);
                }
                dialog.serialPortList.addItem("");
                dialog.setVisible(true);
            }
        });
    }

    public void setState() {
        boolean b = serialPort.isOpened();
        connectButton.setText(b ? "Disconnect" : "Connect");
        serialPortList.setEnabled(!b);
    }

    private void readUrlProcessCommands(ER302Driver.ReceivedStruct result) {
        if (lastCommand == null) {
            return;
        }
        log(lastCommand.id + ". " + lastCommand.descrition + " (data):" + ER302Driver.byteArrayToHexString(result.data));
        switch(result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_READ_FW_VERSION)) -> {
                log("Firmware versino:" + new String(res.data));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                cardSerialNo = res.data;
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_UL)) {
                    log("CardType: MiFARE UltraLight");
                    addCommand(new ER302Driver.CommandStruct(4, "MifareULSelect", mifareULSelect()));
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_REQUEST)) -> {
                typeBytes = res.data;
                addCommand(new ER302Driver.CommandStruct(3, "Mifare anticolision", mifareAnticolision()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_UL_SELECT)) -> {
                addCommand(new ER302Driver.CommandStruct(5, "Mifare read Ultralight", mifareULRead(ulReadPageIdx)));                
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BLOCK)) -> {
                boolean foundURL = false;
                byte[] actualPageData = Arrays.copyOfRange(res.data, 0, 4);
                String pageHexData = ER302Driver.byteArrayToHexString(actualPageData);
                log("Actual page ("+ulReadPageIdx+") bytes: " + pageHexData);
                for (byte b : actualPageData) {
                    if ((b & 0xFF) == 0xFE) {
                        txtURLDownload.setText(ER302Driver.parseNdefUri(rawData.toByteArray()));
                        foundURL = true;
                        break;
                    }
                    rawData.write(b);
                    ulReadIdx++; 
                }                
                if (!foundURL && ulReadPageIdx<40){
                    ulReadPageIdx += 1;
                    addCommand(new ER302Driver.CommandStruct(5, "Mifare read Ultralight", mifareULRead(ulReadPageIdx)));
                }
            }

            default-> log("Skipped command: " + ER302Driver.byteArrayToHexString(result.cmd));
            
        }
    }
    private void iterateCommands(ER302Driver.ReceivedStruct result) {
        if (lastCommand == null) {
            return;
        }
        log(lastCommand.id + ". " + lastCommand.descrition + " (data):" + ER302Driver.byteArrayToHexString(result.data));
        switch(result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_READ_FW_VERSION)) -> {
                log("Firmware versino:" + new String(res.data));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                cardSerialNo = res.data;
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_1K)) {
                    log("CardType: MiFARE Classic 1K");
                    byte[] command = mifareSelect(cardSerialNo);
                    log("Select command:" + ER302Driver.byteArrayToHexString(command));
                    addCommand(new ER302Driver.CommandStruct(4, "MifareSelect", command));
                } else if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_UL)) {
                    log("CardType: MiFARE UltraLight");
                    addCommand(new ER302Driver.CommandStruct(4, "MifareULSelect", mifareULSelect()));
                }
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_REQUEST)) -> {
                typeBytes = res.data;
                addCommand(new ER302Driver.CommandStruct(3, "Mifare anticolision", mifareAnticolision()));
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_SELECT)) -> {
                if (res.error == 0x00) {
                    addCommand(new ER302Driver.CommandStruct(5, "Auth2", auth2((byte)5)));
                } else {
                    log("Select error: " + res.error);
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_UL_SELECT)) -> {
                byte[] ulWriteData = mifareULWrite((byte)8, new byte[]{0x31,0x32,0x33,0x34});
                addCommand(new ER302Driver.CommandStruct(5, "ULWrite (page 8)", ulWriteData));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_UL_WRITE)) -> {
                log("UL write return code: "+ res.error);
                addCommand(new ER302Driver.CommandStruct(6, "Read UL page (8)", readULPage((byte)8)));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_AUTH2)) -> {
                switch(state) {
                    case 0 -> addCommand(new ER302Driver.CommandStruct(6, "Init balance (5/1)", initBalance((byte) 5, (byte) 1, 10)));
                    case 1 -> addCommand(new ER302Driver.CommandStruct(8, "Read balance (5/1)", readBalance((byte) 5, (byte) 1)));
                    case 2 -> addCommand(new ER302Driver.CommandStruct(10, "Inc balance (5/1)", incBalance((byte) 5, (byte) 1, 2)));
                    case 3 -> addCommand(new ER302Driver.CommandStruct(12, "Read balance (5/1)", readBalance((byte) 5, (byte) 1)));
                    case 4 -> addCommand(new ER302Driver.CommandStruct(14, "Dec balance (5/1)", decBalance((byte) 5, (byte) 1, 2)));
                    case 5 -> addCommand(new ER302Driver.CommandStruct(16, "Read balance (5/1)", readBalance((byte) 5, (byte) 1)));
                    case 6 -> addCommand(new ER302Driver.CommandStruct(18, "Read block (5/1)", readBlock((byte) 5, (byte) 0)));
                    case 7 ->  {
                        byte[] byteBlock = {0x00, 0x01, 0x02, 0x03};
                        addCommand(new ER302Driver.CommandStruct(20, "Write block (7/0)", writeBlock((byte) 5, (byte) 0, byteBlock)));
                    }
                    case 8 -> addCommand(new ER302Driver.CommandStruct(22, "Read block (7/0)", readBlock((byte) 5, (byte) 0)));
                    case 9 -> addCommand(new ER302Driver.CommandStruct(24, "Halt", cmdHltA()));
                    default -> System.err.println("Unexpected state: " + state);
                        
                }
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_INITVAL)) -> {
                addCommand(new ER302Driver.CommandStruct(7, "Auth2", auth2((byte) 5)));
                state++;
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_INCREMENT)) -> {
                addCommand(new ER302Driver.CommandStruct(7 + (2 * state), "Auth2", auth2((byte) 5)));
                state++;
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_DECREMENT)) -> {
                addCommand(new ER302Driver.CommandStruct(7 + (2 * state), "Auth2", auth2((byte) 5)));
                state++;
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BALANCE)) -> {
                try {
                    int value = ER302Driver.byteArrayToInteger(res.data, false);             
                    log("Read balance decimal(" + value + ")");
                } catch (IndexOutOfBoundsException ex) {
                    System.err.print(ex.getMessage());
                }
                addCommand(new ER302Driver.CommandStruct(7 + (2 * state), "Auth", auth2((byte) 5)));
                state++;
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BLOCK)) -> {
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_UL)) {
                    log("UL page (8) content: " + ER302Driver.byteArrayToHexString(Arrays.copyOfRange(res.data,0,4)));
                    addCommand(new ER302Driver.CommandStruct(6, "Halt", cmdHltA()));
                } else {
                    addCommand(new ER302Driver.CommandStruct(7 + (2 * state), "Auth", auth2((byte) 5)));
                    state++;
                }
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_WRITE_BLOCK)) -> {
                addCommand(new ER302Driver.CommandStruct(7 + (2 * state), "Auth", auth2((byte) 5)));
                state++;
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_HLTA)) -> {
                log("Halt: "+res.error);
            }
            default -> log("Skipped command: " + ER302Driver.byteArrayToHexString(result.cmd));
        }
    }

    @Override
    public void serialEvent(SerialPortEvent event) {
        if (event.isRXCHAR()) {
            int count = event.getEventValue();
            if (count > 0) {
                log("received bytes count: " + count);
                try {
                    byte[] buffer = serialPort.readBytes(count);
                    log("received buffer[" + ER302Driver.byteArrayToHexString(buffer) + "]");
                    bout.write(buffer);
                    buffer = bout.toByteArray();
                    while ((buffer.length >= 2) && !Arrays.equals(Arrays.copyOf(buffer, 2), ER302Driver.HEADER)) {
                        buffer = Arrays.copyOfRange(buffer, 1, buffer.length);
                    }

                    String input = ER302Driver.byteArrayToHexString(buffer); //"distance:50 mm" 
                    log("received[" + input + "]");
                    ER302Driver.ReceivedStruct result = ER302Driver.decodeReceivedData(buffer);
                    while ((result != null) && (result.length > 0)) {
                        switch(commandsProcessor) {
                            case PROCESS.MESSAGE_SEQUENCE: iterateCommands(result); break;
                            case PROCESS.URL_MESSAGE: readUrlProcessCommands(result); break;
                            default:
                        }
                        if (result.length < buffer.length) {
                            buffer = Arrays.copyOfRange(buffer, result.length, buffer.length);
                        } else {
                            buffer = null;
                        }
                        bout.reset();
                        if ((buffer != null) && buffer.length > 0) {
                            bout.write(buffer);
                            result = ER302Driver.decodeReceivedData(buffer);
                        } else {
                            result = null;
                        }

                    }
                    if (commands.size() > 0) {
                        lastCommand = commands.poll();
                        if (lastCommand != null) {
                            log("Send serial data ["+lastCommand.descrition+"]: "+ER302Driver.byteArrayToHexString(lastCommand.getCmd()));
                            serialPort.writeBytes(lastCommand.getCmd());
                        }
                    }
                } catch (SerialPortException ex) {
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                    log(ex.getMessage());
                } catch (IOException ex) {
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                    log(ex.getMessage());
                }
            }
        }
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnBeep;
    private javax.swing.JButton btnClear;
    private javax.swing.JButton btnDecode;
    private javax.swing.JButton btnDownloadURL;
    private javax.swing.JButton btnEncode;
    private javax.swing.JButton btnSendMessageSequence;
    private javax.swing.JButton btnSendSingleMessage;
    private javax.swing.JButton btnUploadURL;
    private javax.swing.JButton connectButton;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JLabel lblDecode;
    private javax.swing.JTextArea logArea;
    private javax.swing.JComboBox<String> serialPortList;
    private javax.swing.JTextField txtCmd;
    private javax.swing.JTextField txtDecode;
    private javax.swing.JTextField txtHexString;
    private javax.swing.JTextField txtParams;
    private javax.swing.JTextField txtURL;
    private javax.swing.JTextField txtURLDownload;
    // End of variables declaration//GEN-END:variables
}
