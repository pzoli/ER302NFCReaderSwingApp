/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JDialog.java to edit this template
 */
package hu.infokristaly.er302nfcreaderswingapp;

import hu.infokristaly.er302nfcreaderswingapp.ER302Driver.ReceivedStruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
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

    private Queue<ER302Driver.CommandStruct> commands = new LinkedList<ER302Driver.CommandStruct>();
    private Map<Integer, ER302Driver.CommandStruct> commandMap = new HashMap<Integer, ER302Driver.CommandStruct>();

    private enum LED {
        RED, BLUE, OFF
    };

    private ER302Driver.CommandStruct lastCommand;

    private SerialPort serialPort;
    
    private ByteArrayOutputStream bout;

    private static final String newLine = System.getProperty("line.separator");

    private byte[] typeBytes;
    private byte[] cardSerialNo;
    
    private boolean testCommands = false;

    private void addCommand(ER302Driver.CommandStruct cmd) {
        commandMap.put(cmd.id, cmd);
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

    private byte[] mifareULSelect() {
        byte[] data = {};
        byte[] result = buildCommand(ER302Driver.CMD_MIFARE_UL_SELECT, data);
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
        System.out.println("readBlock command: " + ER302Driver.byteArrayToHexString(result));
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
        btnReadCardId = new javax.swing.JButton();
        btnTest = new javax.swing.JButton();
        txtHexString = new javax.swing.JTextField();
        jLabel1 = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);

        jLabel2.setText("Port:");

        connectButton.setText("Connect");
        connectButton.addActionListener(this::connectButtonActionPerformed);

        logArea.setColumns(20);
        logArea.setRows(5);
        jScrollPane1.setViewportView(logArea);

        btnBeep.setText("Beep");
        btnBeep.addActionListener(this::btnBeepActionPerformed);

        btnReadCardId.setText("Send message sequence");
        btnReadCardId.addActionListener(this::btnReadCardIdActionPerformed);

        btnTest.setText("Send");
        btnTest.addActionListener(this::btnTestActionPerformed);

        txtHexString.setText("AABB0500FFFF040004");

        jLabel1.setText("Hexa String:");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(33, 33, 33)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.Alignment.TRAILING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addComponent(jLabel2)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(serialPortList, javax.swing.GroupLayout.PREFERRED_SIZE, 146, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(connectButton, javax.swing.GroupLayout.DEFAULT_SIZE, 146, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(btnBeep)
                            .addComponent(jLabel1, javax.swing.GroupLayout.Alignment.TRAILING))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(txtHexString)
                            .addComponent(btnReadCardId, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnTest)))
                .addGap(39, 39, 39))
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
                    .addComponent(btnReadCardId))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtHexString, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnTest)
                    .addComponent(jLabel1))
                .addContainerGap(13, Short.MAX_VALUE))
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
        byte[] beepMsg = beep((byte) 100);
        try {
            serialPort.writeBytes(beepMsg);
        } catch (SerialPortException ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
            log(ex.getMessage());
        }

    }//GEN-LAST:event_btnBeepActionPerformed

    private void btnReadCardIdActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnReadCardIdActionPerformed
        testCommands = false;
        try {
            logArea.setText("");
            state = 0;
            byte[] statusMsg = buildCommand(ER302Driver.CMD_WORKING_STATUS, new byte[]{0x01, 0x23});
            lastCommand = new ER302Driver.CommandStruct(0, "Working status", statusMsg);
            commandMap.put(0, lastCommand);
            addCommand(new ER302Driver.CommandStruct(1, "Firmware version", readFirmware()));
            addCommand(new ER302Driver.CommandStruct(2, "MiFare request", mifareRequest()));

            log("Status message: "+ER302Driver.byteArrayToHexString(statusMsg));
            serialPort.writeBytes(statusMsg);
        } catch (SerialPortException ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
            log(ex.getMessage());
        }
    }//GEN-LAST:event_btnReadCardIdActionPerformed

    private void btnTestActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnTestActionPerformed
        testCommands = true;
        try {
            logArea.setText("");
            state = 0;
            byte[] msg = ER302Driver.hexStringToByteArray(txtHexString.getText()); //AABB0500FFFF040105
            if (serialPort.writeBytes(msg)) {
                log("Sent string: "+ER302Driver.byteArrayToHexString(msg));
            }
        } catch (SerialPortException ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
            log(ex.getMessage());
        }
    }//GEN-LAST:event_btnTestActionPerformed

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
                    addCommand(new ER302Driver.CommandStruct(4, "MifareSelect", mifareULSelect()));
                }
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_REQUEST)) -> {
                typeBytes = res.data;
                addCommand(new ER302Driver.CommandStruct(3, "Mifare anticolision", mifareAnticolision()));
            } 
            case ReceivedStruct res when ((Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_SELECT) || Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_UL_SELECT)) && res.error == 0x00) -> {
                addCommand(new ER302Driver.CommandStruct(5, "Auth2", auth2((byte)5)));
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_AUTH2)) -> {
                switch(state) {
                    case 0 -> addCommand(new ER302Driver.CommandStruct(6, "Init balance (7/1)", initBalance((byte) 5, (byte) 1, 10)));
                    case 1 -> addCommand(new ER302Driver.CommandStruct(8, "Read balance (7/1)", readBalance((byte) 5, (byte) 1)));
                    case 2 -> addCommand(new ER302Driver.CommandStruct(10, "Inc balance (7/1)", incBalance((byte) 5, (byte) 1, 2)));
                    case 3 -> addCommand(new ER302Driver.CommandStruct(12, "Read balance (7/1)", readBalance((byte) 5, (byte) 1)));
                    case 4 -> addCommand(new ER302Driver.CommandStruct(14, "Dec balance (7/1)", decBalance((byte) 5, (byte) 1, 2)));
                    case 5 -> addCommand(new ER302Driver.CommandStruct(16, "Read balance (7/1)", readBalance((byte) 5, (byte) 1)));
                    case 6 -> addCommand(new ER302Driver.CommandStruct(18, "Read block (7/1)", readBlock((byte) 5, (byte) 0)));
                    case 7 ->  {
                        byte[] byteBlock = {0x00, 0x01, 0x02, 0x03};
                        addCommand(new ER302Driver.CommandStruct(20, "Write block (7/1)", writeBlock((byte) 5, (byte) 0, byteBlock)));
                    }
                    case 8 -> addCommand(new ER302Driver.CommandStruct(22, "Read block (7/1)", readBlock((byte) 5, (byte) 0)));
                    default -> System.err.println("Unexpected value: " + state);
                        
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
                addCommand(new ER302Driver.CommandStruct(7 + (2 * state), "Auth", auth2((byte) 5)));
                state++;
            } 
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_WRITE_BLOCK)) -> {
                addCommand(new ER302Driver.CommandStruct(7 + (2 * state), "Auth", auth2((byte) 5)));
                state++;
            }
            default -> System.out.println("Unexpected value: " + result);
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
                        if (!testCommands)
                            iterateCommands(result);
                        
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
                            System.out.println("Send serial data ["+lastCommand.descrition+"]: "+ER302Driver.byteArrayToHexString(lastCommand.getCmd()));
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
    private javax.swing.JButton btnReadCardId;
    private javax.swing.JButton btnTest;
    private javax.swing.JButton connectButton;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JTextArea logArea;
    private javax.swing.JComboBox<String> serialPortList;
    private javax.swing.JTextField txtHexString;
    // End of variables declaration//GEN-END:variables
}
