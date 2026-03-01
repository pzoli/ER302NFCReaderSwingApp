# ER302NFCReaderSwingApp
Sample JavaSE 25 Swing application for communicate with ER302 (YHY523U) over serial port (JSSC library).
Tested on Ubuntu Linux 17.10/24.04, Windows 10/11 and MacOS 26.3 with MiFare Classic 1K (ISO14443A) card.
On macOS 26.3, add and enable Terminal and NetBeans in System Preferences / Privacy & Security / Full Disk Access. Don't forget to restart them after setup.
Device driver is part of the macOS, no installation required.

## Install ER302 Windows Universal Driver (Windows 10/11)
- make CP210x directory to the filesystem and enter to this
- download the CP210x_Universal_Windows_Driver.zip to this directory from [silabs.com](https://www.silabs.com/software-and-tools/usb-to-uart-bridge-vcp-drivers?tab=downloads) and Unzip files
- Run UpdateParam.bat
- right click on silabser.inf and press Install on the popup menu.

## Build the project
Use maven: mvn clean package

## Run
target > java -jar ER302NFCReaderSwingApp-1.0-SNAPSHOT-jar-with-dependencies.jar

## Usage
Connect ER302 reader to the computer USB port before run the application.

## General functions
### Send message sequence is used for sending messages:
- beep message
- read firmware version
- request MiFare card
- anticolision MiFare card
- select MiFare card
- auth2 to the 5th sector
- initialize a balance at 5th sector 1 block
- read balance
- increment balance
- decrement balance
- read block
- write block
- Hlta

### Send hexa string
With this feature you can send a hexadecimal string to the reader.
(See docs/messages.txt for samples)

### Decode a message
Whit this feature you can decode a message to a visually readable JSON format.
(Output of this message is into the log component)

### Encode a message
With this feature you can encode a command and it's params info a sendable hexadecimal message.
(Output of this function is the Hexa message field component. Than you can send as a message to the ER302)

## Ultralight functions
- NDEF URL upload / download
- NDEF Text upload / download
- NDEF vCard upload / download

## Screenshot (v0.3)

![Screenshot1.png](docs/Screenshot1.png)![Screenshot2.png](docs/Screenshot2.png)

(sponsored by https://nfcshop.hu/)