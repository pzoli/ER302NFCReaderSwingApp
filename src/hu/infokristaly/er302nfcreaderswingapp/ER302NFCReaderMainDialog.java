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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
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
    private int selectedRow = -1;
    private byte currentSector, currentBlock;

    ByteArrayOutputStream rawData = new ByteArrayOutputStream();

    private void sendInitialCommands() throws SerialPortException {
        byte[] beepMsg = Commands.beep((byte) 50);
        lastCommand = new ER302Driver.CommandStruct(0, "Beep", beepMsg);

        addCommand(new ER302Driver.CommandStruct(1, "Firmware version", Commands.readFirmware()));
        addCommand(new ER302Driver.CommandStruct(2, "MiFare request", Commands.mifareRequest()));

        log("Beep message: " + ER302Driver.byteArrayToHexString(beepMsg));
        serialPort.writeBytes(beepMsg);
    }

    private boolean checkPasswordFormat(String password) {
        if (password.length() != 12) {
            return false;
        }
        try {
            ER302Driver.hexStringToByteArray(password);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private boolean checkAccessBitFormat(String accessBits) {
        if (accessBits.length() != 8) {
            return false;
        }
        try {
            ER302Driver.hexStringToByteArray(accessBits);
        } catch (Exception e) {
            return false;
        }
        return true;
    }
    
    private boolean checkNumberInput(String text) {
        try {
            int result = Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return false;
        }
        return true;
    }

    private void processWriteVCardClassicMessage(ReceivedStruct result) {
        switch(result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                String uid = ER302Driver.byteArrayToHexString(res.data);
                log("Anticolision UID:" + uid);
                push(5,"Select " + uid, Commands.mifareSelect(res.data));
            }
            default -> {break;}
        }
    }

    private void push(int i, String name, byte[] cmd) {
        pushCommand(new ER302Driver.CommandStruct(i,name,cmd));
    }

    private void processReadVCardClassicMessage(ReceivedStruct result) {
        switch(result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                String uid = ER302Driver.byteArrayToHexString(res.data);
                log("Anticolision data:" + uid);
                push(5,"Select " + uid, Commands.mifareSelect(res.data));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BLOCK)) -> {
                boolean foundVCard = false;
                byte[] actualPageData = res.data;
                String pageHexData = ER302Driver.byteArrayToHexString(actualPageData); 
                log(String.format("Actual page (%d/%d) bytes: %s", currentSector, currentBlock, pageHexData));
                for (byte b : actualPageData) {
                    if ((b & 0xFF) == 0xFE) {
                        log(Commands.decodeNdefVCard(rawData.toByteArray()));
                        foundVCard = true;
                        break;
                    }
                    rawData.write(b);
                }
                if (res.error == 0 && !foundVCard && currentSector < 40) {
                    currentBlock += 1;

                    if (currentBlock == 3) {
                        currentBlock = 0;
                        currentSector += 1;
                        String key = txtActualKeyForClassic.getText();
                        boolean isKeyA = rbtForClassicKeyA.isSelected();

                        authenticate(currentSector,key,isKeyA);
                    }

                    ER302Driver.CommandStruct command = new ER302Driver.CommandStruct(
                        5,
                        "MiFare read block",
                        Commands.readBlock((byte) currentSector, (byte) currentBlock)
                    );

                    addCommand(command);
                }
            }
            default -> {break;}
        }
    }

    private enum PROCESS {
        SINGLE_MESSAGE, URL_MESSAGE, TEXT_MESSAGE, VCARD_MESSAGE,
        SET_BALANCE_MESSAGE, GET_BALANCE_MESSAGE, INC_BALANCE_MESSAGE, DEC_BALANCE_MESSAGE,
        SETKEY_MESSAGE, GET_ACCESSBITS_MESSAGE, WRITE_VCARD_CLASSIC_MESSAGE, READ_VCARD_CLASSIC_MESSAGE
    };

    private ER302Driver.CommandStruct lastCommand;

    private SerialPort serialPort;

    private ByteArrayOutputStream bout;

    private byte[] typeBytes;
    private byte[] cardSerialNo;
    private byte[] keyBlock;

    private PROCESS commandsProcessor = PROCESS.SINGLE_MESSAGE;

    private final Deque<ER302Driver.CommandStruct> commands = new LinkedList<>();

    private void addCommand(ER302Driver.CommandStruct cmd) {
        commands.add(cmd);
    }

    private void pushCommand(ER302Driver.CommandStruct cmd) {
        commands.addFirst(cmd);
    }

    private void log(String msg) {
        System.out.println(msg);
        if (msg != null) {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
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

        buttonGroup1 = new javax.swing.ButtonGroup();
        buttonGroup2 = new javax.swing.ButtonGroup();
        buttonGroup3 = new javax.swing.ButtonGroup();
        buttonGroup4 = new javax.swing.ButtonGroup();
        buttonGroup5 = new javax.swing.ButtonGroup();
        jLabel2 = new javax.swing.JLabel();
        serialPortList = new javax.swing.JComboBox<>();
        connectButton = new javax.swing.JButton();
        jScrollPane1 = new javax.swing.JScrollPane();
        logArea = new javax.swing.JTextArea();
        jLabel6 = new javax.swing.JLabel();
        jTabbedPane = new javax.swing.JTabbedPane();
        jPanel1 = new javax.swing.JPanel();
        jLabel1 = new javax.swing.JLabel();
        txtHexString = new javax.swing.JTextField();
        btnSendSingleMessage = new javax.swing.JButton();
        btnDecode = new javax.swing.JButton();
        txtDecode = new javax.swing.JTextField();
        lblDecode = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        txtCmd = new javax.swing.JTextField();
        jLabel4 = new javax.swing.JLabel();
        txtParams = new javax.swing.JTextField();
        btnEncode = new javax.swing.JButton();
        btnBeep = new javax.swing.JButton();
        jPanel2 = new javax.swing.JPanel();
        btnUploadURL = new javax.swing.JButton();
        txtURL = new javax.swing.JTextField();
        jLabel5 = new javax.swing.JLabel();
        jLabel7 = new javax.swing.JLabel();
        txtURLDownload = new javax.swing.JTextField();
        btnDownloadURL = new javax.swing.JButton();
        btnUploadText = new javax.swing.JButton();
        txtTextUpload = new javax.swing.JTextField();
        jLabel8 = new javax.swing.JLabel();
        jLabel9 = new javax.swing.JLabel();
        txtTextDownload = new javax.swing.JTextField();
        btnDownloadText = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        txtVCardName = new javax.swing.JTextField();
        jLabel10 = new javax.swing.JLabel();
        jLabel11 = new javax.swing.JLabel();
        txtVCardEmail = new javax.swing.JTextField();
        txtVCardPhone = new javax.swing.JTextField();
        jLabel12 = new javax.swing.JLabel();
        btnUploadVCard = new javax.swing.JButton();
        btnVCardDownload = new javax.swing.JButton();
        jPanel6 = new javax.swing.JPanel();
        jScrollPane2 = new javax.swing.JScrollPane();
        tblPersons = new javax.swing.JTable();
        btnAddPerson = new javax.swing.JButton();
        btnDeletePerson = new javax.swing.JButton();
        btnImportCSVFile = new javax.swing.JButton();
        jLabel23 = new javax.swing.JLabel();
        txtNameForClassic = new javax.swing.JTextField();
        jLabel24 = new javax.swing.JLabel();
        txtEmailFroClassic = new javax.swing.JTextField();
        jLabel25 = new javax.swing.JLabel();
        txtPhoneForClassic = new javax.swing.JTextField();
        btnUpload = new javax.swing.JButton();
        btnDownload = new javax.swing.JButton();
        rbtForClassicKeyA = new javax.swing.JRadioButton();
        rbtForClassicKeyB = new javax.swing.JRadioButton();
        jLabel26 = new javax.swing.JLabel();
        txtActualKeyForClassic = new javax.swing.JTextField();
        jPanel4 = new javax.swing.JPanel();
        jLabel14 = new javax.swing.JLabel();
        txtSector = new javax.swing.JTextField();
        jLabel15 = new javax.swing.JLabel();
        txtBalance = new javax.swing.JTextField();
        txtModification = new javax.swing.JTextField();
        jLabel16 = new javax.swing.JLabel();
        btnInc = new javax.swing.JButton();
        btnDec = new javax.swing.JButton();
        btnBalanceSet = new javax.swing.JButton();
        btnBalanceGet = new javax.swing.JButton();
        jLabel17 = new javax.swing.JLabel();
        txtSectorPassword = new javax.swing.JTextField();
        jLabel18 = new javax.swing.JLabel();
        cbxBlock = new javax.swing.JComboBox<>();
        rbtSectorKeyA = new javax.swing.JRadioButton();
        rbtSectorKeyB = new javax.swing.JRadioButton();
        jPanel5 = new javax.swing.JPanel();
        txtOriginSectorPassword = new javax.swing.JTextField();
        jLabel19 = new javax.swing.JLabel();
        rbtOriginKeyA = new javax.swing.JRadioButton();
        rbtOriginKeyB = new javax.swing.JRadioButton();
        jLabel20 = new javax.swing.JLabel();
        txtNewSectorPassword = new javax.swing.JTextField();
        btnSaveSectorKey = new javax.swing.JButton();
        rbtNewKeyA = new javax.swing.JRadioButton();
        rbtNewKeyB = new javax.swing.JRadioButton();
        txtKeyChangeSector = new javax.swing.JTextField();
        jLabel21 = new javax.swing.JLabel();
        txtAccessBits = new javax.swing.JTextField();
        jLabel22 = new javax.swing.JLabel();
        btnGetAccessBits = new javax.swing.JButton();
        btnClear = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);

        jLabel2.setText("Port:");

        connectButton.setText("Connect");
        connectButton.setPreferredSize(new java.awt.Dimension(72, 23));
        connectButton.addActionListener(this::connectButtonActionPerformed);

        logArea.setColumns(20);
        logArea.setRows(5);
        jScrollPane1.setViewportView(logArea);

        jLabel6.setText("v0.7");

        jLabel1.setText("Hexa String:");

        btnSendSingleMessage.setText("Send");
        btnSendSingleMessage.addActionListener(this::btnSendSingleMessageActionPerformed);

        btnDecode.setText("Decode");
        btnDecode.addActionListener(this::btnDecodeActionPerformed);

        lblDecode.setText("Message:");

        jLabel3.setText("Command:");

        jLabel4.setText("Params:");

        btnEncode.setText("Encode");
        btnEncode.addActionListener(this::btnEncodeActionPerformed);

        btnBeep.setText("Beep");
        btnBeep.addActionListener(this::btnBeepActionPerformed);

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(jLabel1)
                    .addComponent(lblDecode)
                    .addComponent(jLabel3))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(txtDecode)
                    .addComponent(txtHexString, javax.swing.GroupLayout.Alignment.TRAILING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(txtCmd, javax.swing.GroupLayout.DEFAULT_SIZE, 198, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jLabel4)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(txtParams, javax.swing.GroupLayout.PREFERRED_SIZE, 140, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(btnDecode, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnSendSingleMessage, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnEncode, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(btnBeep, javax.swing.GroupLayout.PREFERRED_SIZE, 68, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(223, 223, 223))
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtHexString, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnSendSingleMessage)
                    .addComponent(jLabel1))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblDecode)
                    .addComponent(txtDecode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnDecode))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtCmd, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtParams, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnEncode)
                    .addComponent(jLabel3)
                    .addComponent(jLabel4))
                .addGap(35, 35, 35)
                .addComponent(btnBeep)
                .addContainerGap(104, Short.MAX_VALUE))
        );

        jTabbedPane.addTab("General", jPanel1);

        btnUploadURL.setText("Upload");
        btnUploadURL.addActionListener(this::btnUploadURLActionPerformed);

        jLabel5.setText("URL to Ultralight:");

        jLabel7.setText("URL from Ultralight:");

        btnDownloadURL.setText("Download");
        btnDownloadURL.addActionListener(this::btnDownloadURLActionPerformed);

        btnUploadText.setText("Upload");
        btnUploadText.addActionListener(this::btnUploadTextActionPerformed);

        jLabel8.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel8.setText("Text to Ultralight:");

        jLabel9.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel9.setText("Text from Ultralight:");

        btnDownloadText.setText("Download");
        btnDownloadText.addActionListener(this::btnDownloadTextActionPerformed);

        jPanel3.setBorder(javax.swing.BorderFactory.createTitledBorder("VCard"));

        jLabel10.setText("Name");

        jLabel11.setText("E-mail");

        jLabel12.setText("Phone");

        btnUploadVCard.setText("Upload");
        btnUploadVCard.addActionListener(this::btnUploadVCardActionPerformed);

        btnVCardDownload.setText("Download");
        btnVCardDownload.addActionListener(this::btnVCardDownloadActionPerformed);

        javax.swing.GroupLayout jPanel3Layout = new javax.swing.GroupLayout(jPanel3);
        jPanel3.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(txtVCardName, javax.swing.GroupLayout.PREFERRED_SIZE, 99, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel10, javax.swing.GroupLayout.PREFERRED_SIZE, 43, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(btnUploadVCard, javax.swing.GroupLayout.PREFERRED_SIZE, 99, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnVCardDownload, javax.swing.GroupLayout.PREFERRED_SIZE, 99, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jPanel3Layout.createSequentialGroup()
                                .addComponent(jLabel11, javax.swing.GroupLayout.PREFERRED_SIZE, 43, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(0, 0, Short.MAX_VALUE))
                            .addComponent(txtVCardEmail, javax.swing.GroupLayout.DEFAULT_SIZE, 275, Short.MAX_VALUE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel12, javax.swing.GroupLayout.PREFERRED_SIZE, 43, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(txtVCardPhone, javax.swing.GroupLayout.PREFERRED_SIZE, 148, javax.swing.GroupLayout.PREFERRED_SIZE))))
                .addContainerGap())
        );
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addGap(23, 23, 23)
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(jLabel12)
                        .addGap(27, 27, 27))
                    .addComponent(txtVCardPhone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(jLabel11)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(txtVCardEmail, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addComponent(jLabel10)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(txtVCardName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 10, Short.MAX_VALUE)
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnUploadVCard)
                    .addComponent(btnVCardDownload)))
        );

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel3, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(jPanel2Layout.createSequentialGroup()
                        .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addGroup(jPanel2Layout.createSequentialGroup()
                                .addGap(12, 12, 12)
                                .addComponent(jLabel5)
                                .addGap(3, 3, 3))
                            .addComponent(jLabel7, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(jLabel8, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(jLabel9, javax.swing.GroupLayout.Alignment.TRAILING))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(txtTextUpload, javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(txtTextDownload, javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(txtURL)
                            .addComponent(txtURLDownload))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                .addComponent(btnDownloadText, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(btnUploadURL, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(btnUploadText, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                            .addComponent(btnDownloadURL))))
                .addContainerGap())
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtURL, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnUploadURL)
                    .addComponent(jLabel5))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtURLDownload, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnDownloadURL)
                    .addComponent(jLabel7))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtTextUpload, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnUploadText)
                    .addComponent(jLabel8))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtTextDownload, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnDownloadText)
                    .addComponent(jLabel9))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(0, 0, Short.MAX_VALUE))
        );

        jTabbedPane.addTab("Ultralight", jPanel2);

        tblPersons.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Name", "E-mail", "Phone"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.String.class, java.lang.String.class, java.lang.String.class
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }
        });
        jScrollPane2.setViewportView(tblPersons);

        btnAddPerson.setText("Add person");
        btnAddPerson.addActionListener(this::btnAddPersonActionPerformed);

        btnDeletePerson.setText("Delete person");
        btnDeletePerson.addActionListener(this::btnDeletePersonActionPerformed);

        btnImportCSVFile.setText("Import CSV file");
        btnImportCSVFile.addActionListener(this::btnImportCSVFileActionPerformed);

        jLabel23.setText("Name:");

        jLabel24.setText("E-mail:");

        jLabel25.setText("Phone:");

        btnUpload.setText("Upload");
        btnUpload.addActionListener(this::btnUploadActionPerformed);

        btnDownload.setText("Download");
        btnDownload.addActionListener(this::btnDownloadActionPerformed);

        buttonGroup5.add(rbtForClassicKeyA);
        rbtForClassicKeyA.setText("KeyA");

        buttonGroup5.add(rbtForClassicKeyB);
        rbtForClassicKeyB.setSelected(true);
        rbtForClassicKeyB.setText("KeyB");

        jLabel26.setText("Actual Key:");

        txtActualKeyForClassic.setText("FFFFFFFFFFFF");

        javax.swing.GroupLayout jPanel6Layout = new javax.swing.GroupLayout(jPanel6);
        jPanel6.setLayout(jPanel6Layout);
        jPanel6Layout.setHorizontalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel6Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane2)
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addGroup(jPanel6Layout.createSequentialGroup()
                                .addComponent(jLabel23)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtNameForClassic, javax.swing.GroupLayout.PREFERRED_SIZE, 128, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jLabel24)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtEmailFroClassic))
                            .addGroup(javax.swing.GroupLayout.Alignment.LEADING, jPanel6Layout.createSequentialGroup()
                                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                                    .addGroup(javax.swing.GroupLayout.Alignment.LEADING, jPanel6Layout.createSequentialGroup()
                                        .addComponent(btnAddPerson)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(btnDeletePerson))
                                    .addGroup(javax.swing.GroupLayout.Alignment.LEADING, jPanel6Layout.createSequentialGroup()
                                        .addComponent(jLabel26)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(txtActualKeyForClassic, javax.swing.GroupLayout.PREFERRED_SIZE, 99, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(rbtForClassicKeyA)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(rbtForClassicKeyB)))
                                .addGap(0, 73, Short.MAX_VALUE)))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel6Layout.createSequentialGroup()
                                .addComponent(btnUpload)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnDownload))
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel6Layout.createSequentialGroup()
                                .addComponent(jLabel25)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtPhoneForClassic, javax.swing.GroupLayout.PREFERRED_SIZE, 138, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addComponent(btnImportCSVFile, javax.swing.GroupLayout.Alignment.TRAILING))))
                .addContainerGap())
        );
        jPanel6Layout.setVerticalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel6Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane2, javax.swing.GroupLayout.PREFERRED_SIZE, 152, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnAddPerson)
                    .addComponent(btnDeletePerson)
                    .addComponent(btnImportCSVFile))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel23)
                    .addComponent(txtNameForClassic, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel24)
                    .addComponent(txtEmailFroClassic, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel25)
                    .addComponent(txtPhoneForClassic, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(btnUpload)
                        .addComponent(btnDownload))
                    .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(rbtForClassicKeyA)
                        .addComponent(rbtForClassicKeyB)
                        .addComponent(jLabel26)
                        .addComponent(txtActualKeyForClassic, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(0, 4, Short.MAX_VALUE))
        );

        jTabbedPane.addTab("Classic", jPanel6);

        jLabel14.setText("Sector:");

        txtSector.setText("5");

        jLabel15.setText("Balance:");

        txtBalance.setText("1000");

        txtModification.setText("50");

        jLabel16.setText("Modification:");

        btnInc.setText("Inc");
        btnInc.addActionListener(this::btnIncActionPerformed);

        btnDec.setText("Dec");
        btnDec.addActionListener(this::btnDecActionPerformed);

        btnBalanceSet.setText("Set");
        btnBalanceSet.addActionListener(this::btnBalanceSetActionPerformed);

        btnBalanceGet.setText("Get");
        btnBalanceGet.addActionListener(this::btnBalanceGetActionPerformed);

        jLabel17.setText("Password:");

        txtSectorPassword.setText("FFFFFFFFFFFF");

        jLabel18.setText("Block:");

        cbxBlock.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "0", "1", "2", " " }));

        buttonGroup2.add(rbtSectorKeyA);
        rbtSectorKeyA.setText("KeyA");

        buttonGroup2.add(rbtSectorKeyB);
        rbtSectorKeyB.setSelected(true);
        rbtSectorKeyB.setText("KeyB");

        jPanel5.setBorder(javax.swing.BorderFactory.createTitledBorder("Key change"));

        txtOriginSectorPassword.setText("FFFFFFFFFFFF");

        jLabel19.setText("Origin Key:");

        buttonGroup3.add(rbtOriginKeyA);
        rbtOriginKeyA.setText("KeyA");

        buttonGroup3.add(rbtOriginKeyB);
        rbtOriginKeyB.setSelected(true);
        rbtOriginKeyB.setText("KeyB");

        jLabel20.setText("New Key:");

        txtNewSectorPassword.setText("A1A2A3A4A5A6");

        btnSaveSectorKey.setText("Save");
        btnSaveSectorKey.addActionListener(this::btnSaveSectorKeyActionPerformed);

        buttonGroup4.add(rbtNewKeyA);
        rbtNewKeyA.setSelected(true);
        rbtNewKeyA.setText("KeyA");

        buttonGroup4.add(rbtNewKeyB);
        rbtNewKeyB.setText("KeyB");

        txtKeyChangeSector.setText("5");

        jLabel21.setText("Sector:");

        txtAccessBits.setText("FF078069");

        jLabel22.setText("Key access bits:");

        btnGetAccessBits.setText("Get");
        btnGetAccessBits.addActionListener(this::btnGetAccessBitsActionPerformed);

        javax.swing.GroupLayout jPanel5Layout = new javax.swing.GroupLayout(jPanel5);
        jPanel5.setLayout(jPanel5Layout);
        jPanel5Layout.setHorizontalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(jLabel19)
                    .addComponent(jLabel21)
                    .addComponent(jLabel20))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                        .addComponent(txtNewSectorPassword)
                        .addComponent(txtOriginSectorPassword))
                    .addComponent(txtKeyChangeSector, javax.swing.GroupLayout.PREFERRED_SIZE, 33, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jPanel5Layout.createSequentialGroup()
                                .addComponent(rbtOriginKeyA, javax.swing.GroupLayout.PREFERRED_SIZE, 65, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(rbtOriginKeyB, javax.swing.GroupLayout.PREFERRED_SIZE, 63, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(jPanel5Layout.createSequentialGroup()
                                .addComponent(rbtNewKeyA, javax.swing.GroupLayout.PREFERRED_SIZE, 65, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(rbtNewKeyB, javax.swing.GroupLayout.PREFERRED_SIZE, 63, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                            .addComponent(jLabel22)
                            .addGroup(javax.swing.GroupLayout.Alignment.LEADING, jPanel5Layout.createSequentialGroup()
                                .addGap(6, 6, 6)
                                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(jPanel5Layout.createSequentialGroup()
                                        .addGap(6, 6, 6)
                                        .addComponent(btnGetAccessBits))
                                    .addComponent(txtAccessBits))))
                        .addGap(50, 50, 50))
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addComponent(btnSaveSectorKey)
                        .addGap(49, 286, Short.MAX_VALUE))))
        );
        jPanel5Layout.setVerticalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtOriginSectorPassword, javax.swing.GroupLayout.PREFERRED_SIZE, 24, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel19)
                    .addComponent(rbtOriginKeyA)
                    .addComponent(rbtOriginKeyB)
                    .addComponent(jLabel22))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtNewSectorPassword, javax.swing.GroupLayout.PREFERRED_SIZE, 24, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel20)
                    .addComponent(rbtNewKeyA)
                    .addComponent(rbtNewKeyB)
                    .addComponent(txtAccessBits, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(btnSaveSectorKey))
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel21)
                            .addComponent(txtKeyChangeSector, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnGetAccessBits)
                        .addContainerGap())))
        );

        javax.swing.GroupLayout jPanel4Layout = new javax.swing.GroupLayout(jPanel4);
        jPanel4.setLayout(jPanel4Layout);
        jPanel4Layout.setHorizontalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addComponent(jPanel5, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel4Layout.createSequentialGroup()
                .addGap(0, 0, Short.MAX_VALUE)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel4Layout.createSequentialGroup()
                        .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addGroup(jPanel4Layout.createSequentialGroup()
                                .addComponent(jLabel15)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtBalance, javax.swing.GroupLayout.PREFERRED_SIZE, 54, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(jPanel4Layout.createSequentialGroup()
                                .addComponent(jLabel16)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtModification, javax.swing.GroupLayout.PREFERRED_SIZE, 54, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jPanel4Layout.createSequentialGroup()
                                .addComponent(btnInc)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnDec))
                            .addGroup(jPanel4Layout.createSequentialGroup()
                                .addComponent(btnBalanceSet)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnBalanceGet))))
                    .addGroup(jPanel4Layout.createSequentialGroup()
                        .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jPanel4Layout.createSequentialGroup()
                                .addGap(30, 30, 30)
                                .addComponent(jLabel14)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtSector, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(jLabel18)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(cbxBlock, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(jPanel4Layout.createSequentialGroup()
                                .addGap(10, 10, 10)
                                .addComponent(jLabel17)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtSectorPassword, javax.swing.GroupLayout.PREFERRED_SIZE, 133, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(rbtSectorKeyA)))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(rbtSectorKeyB)))
                .addGap(82, 82, 82))
        );
        jPanel4Layout.setVerticalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel14)
                    .addComponent(txtSector, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel18)
                    .addComponent(cbxBlock, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtSectorPassword, javax.swing.GroupLayout.PREFERRED_SIZE, 24, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel17)
                    .addComponent(rbtSectorKeyB)
                    .addComponent(rbtSectorKeyA))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel15)
                    .addComponent(txtBalance, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnBalanceSet)
                    .addComponent(btnBalanceGet))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtModification, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel16)
                    .addComponent(btnInc)
                    .addComponent(btnDec))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel5, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );

        jTabbedPane.addTab("Micropayment", jPanel4);

        btnClear.setText("Clear");
        btnClear.addActionListener(this::btnClearActionPerformed);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addGap(41, 41, 41)
                        .addComponent(jLabel2)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(serialPortList, javax.swing.GroupLayout.PREFERRED_SIZE, 178, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(connectButton, javax.swing.GroupLayout.PREFERRED_SIZE, 111, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(105, 105, 105)
                        .addComponent(btnClear))
                    .addComponent(jTabbedPane)
                    .addGroup(layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                                .addGap(0, 0, Short.MAX_VALUE)
                                .addComponent(jLabel6, javax.swing.GroupLayout.PREFERRED_SIZE, 37, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addComponent(jScrollPane1))))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(connectButton, javax.swing.GroupLayout.PREFERRED_SIZE, 23, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(jLabel2)
                        .addComponent(btnClear)
                        .addComponent(serialPortList, javax.swing.GroupLayout.PREFERRED_SIZE, 23, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 219, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jTabbedPane)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jLabel6)
                .addContainerGap())
        );

        jTabbedPane.getAccessibleContext().setAccessibleName("Communicatoins");

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void sendCommonULCommands() throws SerialPortException, InterruptedException {
        serialPort.writeBytes(Commands.mifareRequest());
        Thread.sleep(Duration.ofMillis(100));
        serialPort.writeBytes(Commands.mifareAnticolision());
        Thread.sleep(Duration.ofMillis(100));
        serialPort.writeBytes(Commands.mifareULSelect());
    }

    private void processPasswordKeyChange(ReceivedStruct result) {
        if (lastCommand == null) {
            return;
        }
        log(lastCommand.id + ". " + lastCommand.descrition + " (data):" + ER302Driver.byteArrayToHexString(result.data));
        switch (result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_READ_FW_VERSION)) -> {
                log("Firmware versino:" + new String(res.data));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                cardSerialNo = res.data;
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_1K) || Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_4K)) {
                    log("CardType: MiFARE Classic");
                    byte[] command = Commands.mifareSelect(cardSerialNo);
                    log("Select command:" + ER302Driver.byteArrayToHexString(command));
                    addCommand(new ER302Driver.CommandStruct(4, "MifareSelect", command));
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_REQUEST)) -> {
                typeBytes = res.data;
                addCommand(new ER302Driver.CommandStruct(3, "Mifare anticolision", Commands.mifareAnticolision()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_SELECT)) -> {
                byte sector = Byte.parseByte(txtSector.getText());
                if (res.error == 0x00) {
                    addCommand(new ER302Driver.CommandStruct(5, "Auth2", Commands.auth2(sector, txtOriginSectorPassword.getText().trim(), rbtOriginKeyA.isSelected())));
                } else {
                    log("Select error: " + res.error);
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_AUTH2)) -> { //REAUTH NOT NEEDED BEFORE EVERY READ/WRITE
                switch (state) {
                    case 0 ->
                        addCommand(new ER302Driver.CommandStruct(6, "Read block (" + txtKeyChangeSector.getText() + "/3)", Commands.readBlock(Byte.parseByte(txtKeyChangeSector.getText()), (byte) 3)));
                    case 1 -> {
                        byte[] newKeys = ER302Driver.hexStringToByteArray(txtNewSectorPassword.getText());
                        byte[] accessBits = ER302Driver.hexStringToByteArray(txtAccessBits.getText());
                        for(byte i = 0; i < accessBits.length; i++) {
                            keyBlock[6 + i] = accessBits[i];
                        }
                        byte offset = 0;
                        if (!rbtNewKeyA.isSelected()) {
                            offset = 10;
                        }
                        for (byte i = 0; i < newKeys.length; i++) {
                            keyBlock[offset + i] = newKeys[i];
                        }

                        addCommand(new ER302Driver.CommandStruct(8, "Write block (" + txtKeyChangeSector.getText() + "/3)", Commands.writeFullBlock(Byte.parseByte(txtKeyChangeSector.getText()), (byte) 3, keyBlock)));
                    }
                    default ->
                        log("Not handled message");
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BLOCK) && res.error == 0) -> {
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_1K) || Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_4K)) {
                    keyBlock = res.data;
                    byte sector = Byte.parseByte(txtSector.getText());
                    addCommand(new ER302Driver.CommandStruct(7, "Auth", Commands.auth2(sector, txtOriginSectorPassword.getText().trim(), rbtOriginKeyA.isSelected())));
                    state++;
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_WRITE_BLOCK)) -> {
                addCommand(new ER302Driver.CommandStruct(9, "Halt", Commands.cmdHltA()));
                state++;
            }
            default -> {
                log("Unhandled command: " + ER302Driver.byteArrayToHexString(result.cmd));
            }
        }
    }

    private void processBalanceCommads(ReceivedStruct result) {
        if (lastCommand == null) {
            return;
        }
        log(lastCommand.id + ". " + lastCommand.descrition + " (data):" + ER302Driver.byteArrayToHexString(result.data));
        switch (result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_READ_FW_VERSION)) -> {
                log("Firmware versino:" + new String(res.data));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                cardSerialNo = res.data;
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_1K) || Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_4K)) {
                    log("CardType: MiFARE Classic");
                    byte[] command = Commands.mifareSelect(cardSerialNo);
                    log("Select command:" + ER302Driver.byteArrayToHexString(command));
                    addCommand(new ER302Driver.CommandStruct(4, "MifareSelect", command));
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_REQUEST)) -> {
                typeBytes = res.data;
                addCommand(new ER302Driver.CommandStruct(3, "Mifare anticolision", Commands.mifareAnticolision()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_SELECT)) -> {
                byte sector = Byte.parseByte(txtSector.getText());
                if (res.error == 0x00) {
                    addCommand(new ER302Driver.CommandStruct(5, "Auth2", Commands.auth2(sector, txtSectorPassword.getText().trim(), rbtSectorKeyA.isSelected())));
                } else {
                    log("Select error: " + res.error);
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_AUTH2)) -> {
                try {
                    int balance = Integer.parseInt(txtBalance.getText());
                    byte sector = Byte.parseByte(txtSector.getText());
                    byte block = Byte.parseByte(cbxBlock.getSelectedItem().toString());
                    int modification = Integer.parseInt(txtModification.getText());
                    switch (commandsProcessor) {
                        case PROCESS.SET_BALANCE_MESSAGE -> {
                            addCommand(new ER302Driver.CommandStruct(6, "Init balance (" + txtSector.getText() + "/" + cbxBlock.getSelectedItem().toString() + ")", Commands.initBalance(sector, block, balance)));
                        }
                        case PROCESS.GET_BALANCE_MESSAGE -> {
                            addCommand(new ER302Driver.CommandStruct(8, "Read balance (" + txtSector.getText() + "/" + cbxBlock.getSelectedItem().toString() + ")", Commands.readBalance(sector, block)));
                        }
                        case PROCESS.INC_BALANCE_MESSAGE -> {
                            addCommand(new ER302Driver.CommandStruct(10, "Inc balance (" + txtSector.getText() + "/" + cbxBlock.getSelectedItem().toString() + ")", Commands.incBalance(sector, block, modification)));
                        }
                        case PROCESS.DEC_BALANCE_MESSAGE -> {
                            addCommand(new ER302Driver.CommandStruct(12, "Dec balance (" + txtSector.getText() + "/" + cbxBlock.getSelectedItem().toString() + ")", Commands.decBalance(sector, block, modification)));
                        }
                    }
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(null, "Not a valid number format!", "Error", JOptionPane.ERROR_MESSAGE);
                    System.err.println(ex.getMessage());
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_INITVAL)) -> {
                log("Initialized:" + res.error);
                addCommand(new ER302Driver.CommandStruct(14, "Halt", Commands.cmdHltA()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_INCREMENT)) -> {
                log("Increment:" + res.error);
                addCommand(new ER302Driver.CommandStruct(14, "Halt", Commands.cmdHltA()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_DECREMENT)) -> {
                log("Decrement:" + res.error);
                addCommand(new ER302Driver.CommandStruct(14, "Halt", Commands.cmdHltA()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BALANCE)) -> {
                try {
                    int value = ER302Driver.byteArrayToInteger(res.data, false);
                    txtBalance.setText(Integer.toString(value));
                    log("Read balance decimal(" + value + ")");
                } catch (IndexOutOfBoundsException ex) {
                    System.err.print(ex.getMessage());
                }
                addCommand(new ER302Driver.CommandStruct(14, "Halt", Commands.cmdHltA()));
            }
            default -> {
                log("Unhandled command: " + ER302Driver.byteArrayToHexString(result.cmd));
            }
        }
    }

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
                    log("Disconnected.");
                } catch (SerialPortException ex) {
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                    log(ex.getMessage());
                    setState();
                }
            }
        }
    }//GEN-LAST:event_connectButtonActionPerformed

    private void btnUploadVCardActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUploadVCardActionPerformed
        try {
            writeVCardToTag(txtVCardName.getText().trim(), txtVCardPhone.getText().trim(), txtVCardEmail.getText().trim());
        } catch (SerialPortException | IllegalArgumentException | InterruptedException ex) {
            System.getLogger(ER302NFCReaderMainDialog.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }//GEN-LAST:event_btnUploadVCardActionPerformed

    private void btnDownloadTextActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDownloadTextActionPerformed
        commandsProcessor = PROCESS.TEXT_MESSAGE;
        ulReadPageIdx = 4;
        rawData = new ByteArrayOutputStream();
        if (serialPort != null) {
            logArea.setText("");
            try {
                sendInitialCommands();
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnDownloadTextActionPerformed

    private void btnUploadTextActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUploadTextActionPerformed
        try {
            writeTextToTag(txtTextUpload.getText());
        } catch (SerialPortException | InterruptedException | IllegalArgumentException ex) {
            System.getLogger(ER302NFCReaderMainDialog.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }//GEN-LAST:event_btnUploadTextActionPerformed

    private void btnDownloadURLActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDownloadURLActionPerformed
        commandsProcessor = PROCESS.URL_MESSAGE;
        ulReadPageIdx = 4;
        rawData = new ByteArrayOutputStream();
        if (serialPort != null) {
            logArea.setText("");
            try {
                sendInitialCommands();
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnDownloadURLActionPerformed

    private void btnUploadURLActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUploadURLActionPerformed
        try {
            writeUrlToTag(txtURL.getText().trim());
        } catch (SerialPortException | IllegalArgumentException | InterruptedException ex) {
            System.getLogger(ER302NFCReaderMainDialog.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }//GEN-LAST:event_btnUploadURLActionPerformed

    private void btnClearActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearActionPerformed
        logArea.setText("");
    }//GEN-LAST:event_btnClearActionPerformed

    private void btnBeepActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnBeepActionPerformed
        commandsProcessor = PROCESS.SINGLE_MESSAGE;
        byte[] beepMsg = Commands.beep((byte) 100);
        log("Beep message: " + ER302Driver.byteArrayToHexString(beepMsg));
        try {
            serialPort.writeBytes(beepMsg);
        } catch (SerialPortException ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
            log(ex.getMessage());
        }
    }//GEN-LAST:event_btnBeepActionPerformed

    private void btnEncodeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnEncodeActionPerformed
        txtHexString.setText(ER302Driver.byteArrayToHexString(Commands.buildCommand(ER302Driver.hexStringToByteArray(txtCmd.getText()), ER302Driver.hexStringToByteArray(txtParams.getText()))));
    }//GEN-LAST:event_btnEncodeActionPerformed

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

    private void btnSendSingleMessageActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendSingleMessageActionPerformed
        commandsProcessor = PROCESS.SINGLE_MESSAGE;
        if (serialPort != null) {
            try {
                logArea.setText("");
                state = 0;
                txtHexString.setText(txtHexString.getText().replaceAll(" ", ""));
                byte[] msg = ER302Driver.hexStringToByteArray(txtHexString.getText());
                if (serialPort.writeBytes(msg)) {
                    log("Sent string: " + ER302Driver.byteArrayToHexString(msg));
                }
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnSendSingleMessageActionPerformed

    private void btnVCardDownloadActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnVCardDownloadActionPerformed
        commandsProcessor = PROCESS.VCARD_MESSAGE;
        ulReadPageIdx = 4;
        rawData = new ByteArrayOutputStream();
        if (serialPort != null) {
            logArea.setText("");
            try {
                sendInitialCommands();
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnVCardDownloadActionPerformed

    private void btnBalanceSetActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnBalanceSetActionPerformed
        commandsProcessor = PROCESS.SET_BALANCE_MESSAGE;
        if (serialPort != null) {
            logArea.setText("");
            if (!checkPasswordFormat(txtSectorPassword.getText().trim())) {
                JOptionPane.showMessageDialog(null, "Not a valid password key format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkNumberInput(txtBalance.getText())) {
                JOptionPane.showMessageDialog(null, "Not a valid balance number format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkNumberInput(txtModification.getText())) {
                JOptionPane.showMessageDialog(null, "Not a valid modification number format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                sendInitialCommands();
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnBalanceSetActionPerformed

    private void btnBalanceGetActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnBalanceGetActionPerformed
        commandsProcessor = PROCESS.GET_BALANCE_MESSAGE;
        if (serialPort != null) {
            logArea.setText("");
            if (!checkPasswordFormat(txtSectorPassword.getText().trim())) {
                JOptionPane.showMessageDialog(null, "Not a valid password key format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkNumberInput(txtBalance.getText())) {
                JOptionPane.showMessageDialog(null, "Not a valid balance number format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkNumberInput(txtModification.getText())) {
                JOptionPane.showMessageDialog(null, "Not a valid modification number format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                sendInitialCommands();
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnBalanceGetActionPerformed

    private void btnIncActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnIncActionPerformed
        commandsProcessor = PROCESS.INC_BALANCE_MESSAGE;
        if (serialPort != null) {
            logArea.setText("");
            if (!checkPasswordFormat(txtSectorPassword.getText().trim())) {
                JOptionPane.showMessageDialog(null, "Not a valid password key format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkNumberInput(txtBalance.getText())) {
                JOptionPane.showMessageDialog(null, "Not a valid balance number format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkNumberInput(txtModification.getText())) {
                JOptionPane.showMessageDialog(null, "Not a valid modification number format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                sendInitialCommands();
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnIncActionPerformed

    private void btnDecActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDecActionPerformed
        commandsProcessor = PROCESS.DEC_BALANCE_MESSAGE;
        if (serialPort != null) {
            logArea.setText("");
            if (!checkPasswordFormat(txtSectorPassword.getText().trim())) {
                JOptionPane.showMessageDialog(null, "Not a valid password key format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkNumberInput(txtBalance.getText())) {
                JOptionPane.showMessageDialog(null, "Not a valid balance number format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkNumberInput(txtModification.getText())) {
                JOptionPane.showMessageDialog(null, "Not a valid modification number format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                sendInitialCommands();
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnDecActionPerformed

    private void btnSaveSectorKeyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveSectorKeyActionPerformed
        commandsProcessor = PROCESS.SETKEY_MESSAGE;
        state = 0;
        if (serialPort != null) {
            logArea.setText("");
            if (!checkPasswordFormat(txtOriginSectorPassword.getText().trim())) {
                JOptionPane.showMessageDialog(null, "Not a valid origin password key format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkPasswordFormat(txtNewSectorPassword.getText().trim())) {
                JOptionPane.showMessageDialog(null, "Not a valid new password key format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!checkAccessBitFormat(txtAccessBits.getText().trim())) {
                JOptionPane.showMessageDialog(null, "Not a valid access bits format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                sendInitialCommands();
            } catch (SerialPortException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                log(ex.getMessage());
            }
        }
    }//GEN-LAST:event_btnSaveSectorKeyActionPerformed

    private void btnGetAccessBitsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnGetAccessBitsActionPerformed
        commandsProcessor = PROCESS.GET_ACCESSBITS_MESSAGE;
        if (serialPort != null) {
            logArea.setText("");
            if (!checkPasswordFormat(txtOriginSectorPassword.getText().trim())) {
                JOptionPane.showMessageDialog(null, "Not a valid password key format!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        try {
            sendInitialCommands();
        } catch (SerialPortException ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
            log(ex.getMessage());
        }
    }//GEN-LAST:event_btnGetAccessBitsActionPerformed

    private static void importCSV(DefaultTableModel model) {
        JFileChooser fileChooser = new JFileChooser();
        int response = fileChooser.showOpenDialog(null);

        if (response == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            
            model.setRowCount(0);

            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                String header = br.readLine();
                while ((line = br.readLine()) != null) {
                    String[] data = line.split(",");
                    if (data.length >= 3) {
                        model.addRow(new Object[]{data[0].trim(), data[1].trim(), data[2].trim()});
                    }
                }
                JOptionPane.showMessageDialog(null, "Import successful!");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(null, "Error on importing CSV file: " + ex.getMessage());
            }
        }
    }

    void add(int id, String description, byte[] cmd) {
        addCommand(new ER302Driver.CommandStruct(id, description, cmd));
    }   
    
    private void authenticate(int sector, String key, boolean useKeyA) {
        add(4, "Auth sector " + sector, Commands.auth2((byte) sector, key, useKeyA));
    }
    
    private void btnImportCSVFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnImportCSVFileActionPerformed
        importCSV((DefaultTableModel)tblPersons.getModel());
    }//GEN-LAST:event_btnImportCSVFileActionPerformed

    private void btnAddPersonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAddPersonActionPerformed
        ((DefaultTableModel)tblPersons.getModel()).addRow(new Object[]{"", "", ""});
        int lastRow = tblPersons.getRowCount() - 1;    
        tblPersons.setRowSelectionInterval(lastRow, lastRow);
        tblPersons.scrollRectToVisible(tblPersons.getCellRect(lastRow, 0, true));
    }//GEN-LAST:event_btnAddPersonActionPerformed

    private void btnDeletePersonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDeletePersonActionPerformed
        if (selectedRow != -1) {
            ((DefaultTableModel)tblPersons.getModel()).removeRow(selectedRow);
        }
    }//GEN-LAST:event_btnDeletePersonActionPerformed

    private void btnUploadActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUploadActionPerformed
        if (serialPort == null) {
            return;
        }
        if (!checkPasswordFormat(txtActualKeyForClassic.getText().trim())) {
            JOptionPane.showMessageDialog(null, "Not a valid actual key format!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        commandsProcessor = PROCESS.WRITE_VCARD_CLASSIC_MESSAGE;
        logArea.setText("");
        String key = txtActualKeyForClassic.getText();
        boolean isKeyA = rbtForClassicKeyA.isSelected();
        byte[] ndef = Commands.createNdefVCardMessage(txtNameForClassic.getText(), txtPhoneForClassic.getText(), txtEmailFroClassic.getText());
        
        add(1, "MiFare Request", Commands.mifareRequest());
        add(2, "MiFare Anticolision", Commands.mifareAnticolision());

        authenticate(0,key,isKeyA);
        byte[] mad1 = ER302Driver.hexStringToByteArray("140103E103E103E103E103E103E103E1");
        add(3, "Write MAD1 (S0 B1)", Commands.writeFullBlock((byte) 0, (byte) 1, mad1));

        byte[] mad2 = ER302Driver.hexStringToByteArray("03E103E103E103E103E103E103E103E1");
        add(3, "Write MAD2 (S0 B2)", Commands.writeFullBlock((byte) 0, (byte) 2, mad2));

        // Sector 0 Trailer
        byte[] madTrailer = {
            (byte) 0xA0, (byte) 0xA1, (byte) 0xA2, (byte) 0xA3, (byte) 0xA4, (byte) 0xA5,
            (byte) 0x78, (byte) 0x77, (byte) 0x88,
            (byte) 0xC1,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };

        byte[] madTrailerOthers = {
            (byte) 0xD3, (byte) 0xF7, (byte) 0xD3, (byte) 0xF7, (byte) 0xD3, (byte) 0xF7,
            (byte) 0x7F, (byte) 0x07, (byte) 0x88,
            (byte) 0x40,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };

        add(4, "Write MAD Trailer (S0 B3)", Commands.writeFullBlock((byte) 0, (byte) 3, madTrailer));

        byte[] remainingBytes = ndef;
        int sector = 1;
        int blockInSector = 0;
        int offset = 0;

        authenticate(sector,key,isKeyA);

        while (offset < remainingBytes.length) {
            int blocksPerSector = (sector < 32) ? 4 : 16;
            int trailerBlock = blocksPerSector - 1;

            if (blockInSector == trailerBlock) {
                add(4, "Write MAD Trailer (S" + sector + " B" + trailerBlock + ")", 
                    Commands.writeFullBlock((byte) sector, (byte) blockInSector, madTrailerOthers));
                
                sector++;
                blockInSector = 0;
                authenticate(sector,key,isKeyA);
                continue;
            }

            byte[] block = new byte[16];
            int lengthToCopy = Math.min(16, remainingBytes.length - offset);
            System.arraycopy(remainingBytes, offset, block, 0, lengthToCopy);

            add(5, "Write S" + sector + " B" + blockInSector, 
                Commands.writeFullBlock((byte) sector, (byte) blockInSector, block));
            
            add(4, "Write MAD Trailer (S" + sector + " B" + trailerBlock + ")", 
                Commands.writeFullBlock((byte) sector, (byte) (byte) trailerBlock, madTrailerOthers));

            offset += lengthToCopy;
            blockInSector++;
        }

        add(6, "MiFare HltA", Commands.cmdHltA());
        try {
            byte[] beepMsg = Commands.beep((byte) 50);
            lastCommand = new ER302Driver.CommandStruct(0, "Beep", beepMsg);
            serialPort.writeBytes(beepMsg);
        } catch (SerialPortException ex) {
            System.getLogger(ER302NFCReaderMainDialog.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
                
    }//GEN-LAST:event_btnUploadActionPerformed

    private void btnDownloadActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDownloadActionPerformed
        if (serialPort == null) {
            return;
        }
        if (!checkPasswordFormat(txtActualKeyForClassic.getText().trim())) {
            JOptionPane.showMessageDialog(null, "Not a valid actual key format!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        commandsProcessor = PROCESS.READ_VCARD_CLASSIC_MESSAGE;
        logArea.setText("");
        rawData = new ByteArrayOutputStream();
        currentSector = 1;
        currentBlock = 0;
        addCommand(new ER302Driver.CommandStruct(1, "Firmware version", Commands.readFirmware()));
        addCommand(new ER302Driver.CommandStruct(2, "MiFare request", Commands.mifareRequest()));
        addCommand(new ER302Driver.CommandStruct(3, "MiFare anticolision", Commands.mifareAnticolision()));
        String key = txtActualKeyForClassic.getText();
        boolean isKeyA = rbtForClassicKeyA.isSelected();
        authenticate(currentSector, key, isKeyA);
        addCommand(new ER302Driver.CommandStruct(4, "MiFare read block", Commands.readBlock(currentSector, currentBlock)));
        try {
            byte[] beepMsg = Commands.beep((byte) 50);
            lastCommand = new ER302Driver.CommandStruct(0, "Beep", beepMsg);
            serialPort.writeBytes(beepMsg);
        } catch (SerialPortException ex) {
            System.getLogger(ER302NFCReaderMainDialog.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }//GEN-LAST:event_btnDownloadActionPerformed

    public void writeVCardToTag(String vCardName, String vCardPhone, String vCardEmail) throws InterruptedException, SerialPortException {
        commandsProcessor = PROCESS.SINGLE_MESSAGE;
        sendCommonULCommands();
        Thread.sleep(Duration.ofMillis(100));
        byte[] dataToWrite = Commands.createNdefVCardMessage(vCardName, vCardPhone, vCardEmail);

        for (int i = 0; i < dataToWrite.length; i += 4) {
            byte[] chunk = new byte[4];
            int remaining = dataToWrite.length - i;
            System.arraycopy(dataToWrite, i, chunk, 0, Math.min(4, remaining));

            byte page = (byte) (4 + (i / 4));
            byte[] cmd = Commands.mifareULWrite(page, chunk);

            serialPort.writeBytes(cmd);
            Thread.sleep(Duration.ofMillis(100));
        }

    }

    public void writeUrlToTag(String url) throws SerialPortException, InterruptedException, IllegalArgumentException {
        commandsProcessor = PROCESS.SINGLE_MESSAGE;
        sendCommonULCommands();
        Thread.sleep(Duration.ofMillis(100));
        byte[] dataToWrite = Commands.createNdefUrlMessage(url);

        for (int i = 0; i < dataToWrite.length; i += 4) {
            byte[] chunk = new byte[4];
            int remaining = dataToWrite.length - i;
            System.arraycopy(dataToWrite, i, chunk, 0, Math.min(4, remaining));

            int page = 4 + (i / 4);
            byte[] pcmd = Commands.mifareULWrite((byte) page, chunk);
            serialPort.writeBytes(pcmd);
            Thread.sleep(Duration.ofMillis(100));
            log("Writing page " + page + ": " + ER302Driver.byteArrayToHexString(chunk));
        }
        serialPort.writeBytes(Commands.cmdHltA());
    }

    public void writeTextToTag(String text) throws SerialPortException, InterruptedException, IllegalArgumentException {
        commandsProcessor = PROCESS.SINGLE_MESSAGE;
        sendCommonULCommands();
        Thread.sleep(Duration.ofMillis(100));
        byte[] dataToWrite = Commands.createNdefTextMessage(text);

        for (int i = 0; i < dataToWrite.length; i += 4) {
            byte[] chunk = new byte[4];
            int remaining = dataToWrite.length - i;
            System.arraycopy(dataToWrite, i, chunk, 0, Math.min(4, remaining));

            int page = 4 + (i / 4);
            byte[] pcmd = Commands.mifareULWrite((byte) page, chunk);
            serialPort.writeBytes(pcmd);
            Thread.sleep(Duration.ofMillis(100));
            log("Writing page " + page + ": " + ER302Driver.byteArrayToHexString(chunk));
        }
        serialPort.writeBytes(Commands.cmdHltA());
    }

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
                
                String[] columns = {"Name", "E-mail", "Phone"};
                DefaultTableModel model = new DefaultTableModel(columns, 0);
                dialog.tblPersons.setModel(model);
                dialog.tblPersons.getSelectionModel().addListSelectionListener(e -> {

                    if (!e.getValueIsAdjusting()) {
                        dialog.selectedRow = dialog.tblPersons.getSelectedRow();
                        if (dialog.selectedRow != -1) {
                            String name = dialog.tblPersons.getValueAt(dialog.selectedRow, 0).toString();
                            String email = dialog.tblPersons.getValueAt(dialog.selectedRow, 1).toString();
                            String phone = dialog.tblPersons.getValueAt(dialog.selectedRow, 2).toString();

                            dialog.txtNameForClassic.setText(name);
                            dialog.txtEmailFroClassic.setText(email);
                            dialog.txtPhoneForClassic.setText(phone);
                        }
                    }
                });
                
                dialog.tblPersons.getModel().addTableModelListener(e -> {
                    if (e.getType() == TableModelEvent.UPDATE && dialog.tblPersons.isEditing()) {
                        int row = e.getFirstRow();
                        int column = e.getColumn();

                        int selectedRow = dialog.tblPersons.getSelectedRow();

                        if (row == selectedRow && row != -1) {
                            Object newValue = dialog.tblPersons.getValueAt(row, column);
                            String valueStr = (newValue != null) ? newValue.toString() : "";

                            switch (column) {
                                case 0 -> dialog.txtNameForClassic.setText(valueStr);
                                case 1 -> dialog.txtEmailFroClassic.setText(valueStr);
                                case 2 -> dialog.txtPhoneForClassic.setText(valueStr);
                            }
                        }
                    }
                });
                
                dialog.txtNameForClassic.getDocument().addDocumentListener(new DocumentListener() {
                    public void changedUpdate(DocumentEvent e) { updateTable(); }
                    public void removeUpdate(DocumentEvent e) { updateTable(); }
                    public void insertUpdate(DocumentEvent e) { updateTable(); }

                    private void updateTable() {
                        int selectedRow = dialog.tblPersons.getSelectedRow();
                        if (selectedRow != -1) {
                            if (dialog.tblPersons.isEditing()) {
                                dialog.tblPersons.getCellEditor().cancelCellEditing();
                            }
                            dialog.tblPersons.getModel().setValueAt(dialog.txtNameForClassic.getText(), selectedRow, 0);
                        }
                    }
                });

                dialog.txtEmailFroClassic.getDocument().addDocumentListener(new DocumentListener() {
                    public void changedUpdate(DocumentEvent e) { updateTable(); }
                    public void removeUpdate(DocumentEvent e) { updateTable(); }
                    public void insertUpdate(DocumentEvent e) { updateTable(); }

                    private void updateTable() {
                        int selectedRow = dialog.tblPersons.getSelectedRow();
                        if (selectedRow != -1) {
                            if (dialog.tblPersons.isEditing()) {
                                dialog.tblPersons.getCellEditor().cancelCellEditing();
                            }
                            dialog.tblPersons.getModel().setValueAt(dialog.txtEmailFroClassic.getText(), selectedRow, 1);
                        }
                    }
                });

                dialog.txtPhoneForClassic.getDocument().addDocumentListener(new DocumentListener() {
                    public void changedUpdate(DocumentEvent e) { updateTable(); }
                    public void removeUpdate(DocumentEvent e) { updateTable(); }
                    public void insertUpdate(DocumentEvent e) { updateTable(); }

                    private void updateTable() {
                        int selectedRow = dialog.tblPersons.getSelectedRow();
                        if (selectedRow != -1) {
                            if (dialog.tblPersons.isEditing()) {
                                dialog.tblPersons.getCellEditor().cancelCellEditing();
                            }
                            dialog.tblPersons.getModel().setValueAt(dialog.txtPhoneForClassic.getText(), selectedRow, 2);
                        }
                    }
                });

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
                dialog.setLocationRelativeTo(null);
                dialog.setTitle("ER302 NFC reader App");
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
        switch (result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_READ_FW_VERSION)) -> {
                log("Firmware versino:" + new String(res.data));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                cardSerialNo = res.data;
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_UL)) {
                    log("CardType: MiFARE UltraLight");
                    addCommand(new ER302Driver.CommandStruct(4, "MifareULSelect", Commands.mifareULSelect()));
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_REQUEST)) -> {
                typeBytes = res.data;
                addCommand(new ER302Driver.CommandStruct(3, "Mifare anticolision", Commands.mifareAnticolision()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_UL_SELECT)) -> {
                addCommand(new ER302Driver.CommandStruct(5, "Mifare read Ultralight", Commands.mifareULRead(ulReadPageIdx)));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BLOCK)) -> {
                boolean foundURL = false;
                byte[] actualPageData = Arrays.copyOfRange(res.data, 0, 4);
                String pageHexData = ER302Driver.byteArrayToHexString(actualPageData);
                log("Actual page (" + ulReadPageIdx + ") bytes: " + pageHexData);
                for (byte b : actualPageData) {
                    if ((b & 0xFF) == 0xFE) {
                        txtURLDownload.setText(Commands.parseNdefUri(rawData.toByteArray()));
                        foundURL = true;
                        break;
                    }
                    rawData.write(b);
                    ulReadIdx++;
                }
                if (!foundURL && ulReadPageIdx < 40) {
                    ulReadPageIdx += 1;
                    addCommand(new ER302Driver.CommandStruct(5, "Mifare read Ultralight", Commands.mifareULRead(ulReadPageIdx)));
                }
            }

            default ->
                log("Skipped command: " + ER302Driver.byteArrayToHexString(result.cmd));

        }
    }

    private void readTextProcessCommands(ER302Driver.ReceivedStruct result) {
        if (lastCommand == null) {
            return;
        }
        log(lastCommand.id + ". " + lastCommand.descrition + " (data):" + ER302Driver.byteArrayToHexString(result.data));
        switch (result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_READ_FW_VERSION)) -> {
                log("Firmware versino:" + new String(res.data));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                cardSerialNo = res.data;
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_UL)) {
                    log("CardType: MiFARE UltraLight");
                    addCommand(new ER302Driver.CommandStruct(4, "MifareULSelect", Commands.mifareULSelect()));
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_REQUEST)) -> {
                typeBytes = res.data;
                addCommand(new ER302Driver.CommandStruct(3, "Mifare anticolision", Commands.mifareAnticolision()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_UL_SELECT)) -> {
                addCommand(new ER302Driver.CommandStruct(5, "Mifare read Ultralight", Commands.mifareULRead(ulReadPageIdx)));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BLOCK)) -> {
                boolean foundURL = false;
                byte[] actualPageData = Arrays.copyOfRange(res.data, 0, 4);
                String pageHexData = ER302Driver.byteArrayToHexString(actualPageData);
                log("Actual page (" + ulReadPageIdx + ") bytes: " + pageHexData);
                for (byte b : actualPageData) {
                    if ((b & 0xFF) == 0xFE) {
                        txtTextDownload.setText(Commands.decodeNdefText(rawData.toByteArray()));
                        foundURL = true;
                        break;
                    }
                    rawData.write(b);
                    ulReadIdx++;
                }
                if (!foundURL && ulReadPageIdx < 40) {
                    ulReadPageIdx += 1;
                    addCommand(new ER302Driver.CommandStruct(5, "Mifare read Ultralight", Commands.mifareULRead(ulReadPageIdx)));
                }
            }

            default ->
                log("Skipped command: " + ER302Driver.byteArrayToHexString(result.cmd));

        }
    }

    private void readVCardProcessCommands(ER302Driver.ReceivedStruct result) {
        if (lastCommand == null) {
            return;
        }
        log(lastCommand.id + ". " + lastCommand.descrition + " (data):" + ER302Driver.byteArrayToHexString(result.data));
        switch (result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_READ_FW_VERSION)) -> {
                log("Firmware versino:" + new String(res.data));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                cardSerialNo = res.data;
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_UL)) {
                    log("CardType: MiFARE UltraLight");
                    addCommand(new ER302Driver.CommandStruct(4, "MifareULSelect", Commands.mifareULSelect()));
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_REQUEST)) -> {
                typeBytes = res.data;
                addCommand(new ER302Driver.CommandStruct(3, "Mifare anticolision", Commands.mifareAnticolision()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_UL_SELECT)) -> {
                addCommand(new ER302Driver.CommandStruct(5, "Mifare read Ultralight", Commands.mifareULRead(ulReadPageIdx)));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BLOCK)) -> {
                boolean foundVCard = false;
                byte[] actualPageData = Arrays.copyOfRange(res.data, 0, 4);
                String pageHexData = ER302Driver.byteArrayToHexString(actualPageData);
                log("Actual page (" + ulReadPageIdx + ") bytes: " + pageHexData);
                for (byte b : actualPageData) {
                    if ((b & 0xFF) == 0xFE) {
                        logArea.setText(Commands.decodeNdefVCard(rawData.toByteArray())+"\n");
                        foundVCard = true;
                        break;
                    }
                    rawData.write(b);
                    ulReadIdx++;
                }
                if (!foundVCard && ulReadPageIdx < 40) {
                    ulReadPageIdx += 1;
                    addCommand(new ER302Driver.CommandStruct(5, "Mifare read Ultralight", Commands.mifareULRead(ulReadPageIdx)));
                }
            }

            default ->
                log("Skipped command: " + ER302Driver.byteArrayToHexString(result.cmd));

        }
    }

    private void processGetAccessBits(ReceivedStruct result) {
        if (lastCommand == null) {
            return;
        }
        log(lastCommand.id + ". " + lastCommand.descrition + " (data):" + ER302Driver.byteArrayToHexString(result.data));
        switch (result) {
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_READ_FW_VERSION)) -> {
                log("Firmware versino:" + new String(res.data));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_ANTICOLISION)) -> {
                cardSerialNo = res.data;
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_1K) || Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_4K)) {
                    log("CardType: MiFARE Classic");
                    byte[] command = Commands.mifareSelect(cardSerialNo);
                    log("Select command:" + ER302Driver.byteArrayToHexString(command));
                    addCommand(new ER302Driver.CommandStruct(4, "MifareSelect", command));
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_REQUEST)) -> {
                typeBytes = res.data;
                addCommand(new ER302Driver.CommandStruct(3, "Mifare anticolision", Commands.mifareAnticolision()));
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_SELECT)) -> {
                byte sector = Byte.parseByte(txtSector.getText());
                if (res.error == 0x00) {
                    addCommand(new ER302Driver.CommandStruct(5, "Auth2", Commands.auth2(sector, txtOriginSectorPassword.getText().trim(), rbtOriginKeyA.isSelected())));
                } else {
                    log("Select error: " + res.error);
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_AUTH2)) -> {
                switch (state) {
                    case 0 ->
                        addCommand(new ER302Driver.CommandStruct(6, "Read block (" + txtKeyChangeSector.getText() + "/3)", Commands.readBlock(Byte.parseByte(txtKeyChangeSector.getText()), (byte) 3)));
                    default ->
                        log("Not handled message");
                }
            }
            case ReceivedStruct res when (Arrays.equals(res.cmd, ER302Driver.CMD_MIFARE_READ_BLOCK) && res.error == 0) -> {
                if (Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_1K) || Arrays.equals(typeBytes, ER302Driver.TYPE_MIFARE_4K)) {
                    keyBlock = res.data;
                    String sAccessBits = ER302Driver.byteArrayToHexString(keyBlock);
                    txtAccessBits.setText(sAccessBits.substring(12, 20));
                    addCommand(new ER302Driver.CommandStruct(9, "Halt", Commands.cmdHltA()));
                }
            }
            default -> {
                log("Unhandled command: " + ER302Driver.byteArrayToHexString(result.cmd));
            }
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
                        switch (commandsProcessor) {
                            case PROCESS.URL_MESSAGE:
                                readUrlProcessCommands(result);
                                break;
                            case PROCESS.TEXT_MESSAGE:
                                readTextProcessCommands(result);
                                break;
                            case PROCESS.VCARD_MESSAGE:
                                readVCardProcessCommands(result);
                                break;
                            case PROCESS.SET_BALANCE_MESSAGE:
                            case PROCESS.GET_BALANCE_MESSAGE:
                            case PROCESS.INC_BALANCE_MESSAGE:
                            case PROCESS.DEC_BALANCE_MESSAGE:
                                processBalanceCommads(result);
                                break;
                            case PROCESS.SETKEY_MESSAGE:
                                processPasswordKeyChange(result);
                                break;
                            case PROCESS.GET_ACCESSBITS_MESSAGE:
                                processGetAccessBits(result);
                                break;
                            case PROCESS.WRITE_VCARD_CLASSIC_MESSAGE:
                                processWriteVCardClassicMessage(result);
                                break;
                            case PROCESS.READ_VCARD_CLASSIC_MESSAGE:
                                processReadVCardClassicMessage(result);
                                break;
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
                            log("Send serial data [" + lastCommand.descrition + "]: " + ER302Driver.byteArrayToHexString(lastCommand.getCmd()));
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
    private javax.swing.JButton btnAddPerson;
    private javax.swing.JButton btnBalanceGet;
    private javax.swing.JButton btnBalanceSet;
    private javax.swing.JButton btnBeep;
    private javax.swing.JButton btnClear;
    private javax.swing.JButton btnDec;
    private javax.swing.JButton btnDecode;
    private javax.swing.JButton btnDeletePerson;
    private javax.swing.JButton btnDownload;
    private javax.swing.JButton btnDownloadText;
    private javax.swing.JButton btnDownloadURL;
    private javax.swing.JButton btnEncode;
    private javax.swing.JButton btnGetAccessBits;
    private javax.swing.JButton btnImportCSVFile;
    private javax.swing.JButton btnInc;
    private javax.swing.JButton btnSaveSectorKey;
    private javax.swing.JButton btnSendSingleMessage;
    private javax.swing.JButton btnUpload;
    private javax.swing.JButton btnUploadText;
    private javax.swing.JButton btnUploadURL;
    private javax.swing.JButton btnUploadVCard;
    private javax.swing.JButton btnVCardDownload;
    private javax.swing.ButtonGroup buttonGroup1;
    private javax.swing.ButtonGroup buttonGroup2;
    private javax.swing.ButtonGroup buttonGroup3;
    private javax.swing.ButtonGroup buttonGroup4;
    private javax.swing.ButtonGroup buttonGroup5;
    private javax.swing.JComboBox<String> cbxBlock;
    private javax.swing.JButton connectButton;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel14;
    private javax.swing.JLabel jLabel15;
    private javax.swing.JLabel jLabel16;
    private javax.swing.JLabel jLabel17;
    private javax.swing.JLabel jLabel18;
    private javax.swing.JLabel jLabel19;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel20;
    private javax.swing.JLabel jLabel21;
    private javax.swing.JLabel jLabel22;
    private javax.swing.JLabel jLabel23;
    private javax.swing.JLabel jLabel24;
    private javax.swing.JLabel jLabel25;
    private javax.swing.JLabel jLabel26;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JPanel jPanel6;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JTabbedPane jTabbedPane;
    private javax.swing.JLabel lblDecode;
    private javax.swing.JTextArea logArea;
    private javax.swing.JRadioButton rbtForClassicKeyA;
    private javax.swing.JRadioButton rbtForClassicKeyB;
    private javax.swing.JRadioButton rbtNewKeyA;
    private javax.swing.JRadioButton rbtNewKeyB;
    private javax.swing.JRadioButton rbtOriginKeyA;
    private javax.swing.JRadioButton rbtOriginKeyB;
    private javax.swing.JRadioButton rbtSectorKeyA;
    private javax.swing.JRadioButton rbtSectorKeyB;
    private javax.swing.JComboBox<String> serialPortList;
    private javax.swing.JTable tblPersons;
    private javax.swing.JTextField txtAccessBits;
    private javax.swing.JTextField txtActualKeyForClassic;
    private javax.swing.JTextField txtBalance;
    private javax.swing.JTextField txtCmd;
    private javax.swing.JTextField txtDecode;
    private javax.swing.JTextField txtEmailFroClassic;
    private javax.swing.JTextField txtHexString;
    private javax.swing.JTextField txtKeyChangeSector;
    private javax.swing.JTextField txtModification;
    private javax.swing.JTextField txtNameForClassic;
    private javax.swing.JTextField txtNewSectorPassword;
    private javax.swing.JTextField txtOriginSectorPassword;
    private javax.swing.JTextField txtParams;
    private javax.swing.JTextField txtPhoneForClassic;
    private javax.swing.JTextField txtSector;
    private javax.swing.JTextField txtSectorPassword;
    private javax.swing.JTextField txtTextDownload;
    private javax.swing.JTextField txtTextUpload;
    private javax.swing.JTextField txtURL;
    private javax.swing.JTextField txtURLDownload;
    private javax.swing.JTextField txtVCardEmail;
    private javax.swing.JTextField txtVCardName;
    private javax.swing.JTextField txtVCardPhone;
    // End of variables declaration//GEN-END:variables
}
