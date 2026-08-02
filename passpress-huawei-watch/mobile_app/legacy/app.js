const UART_SERVICE_UUID = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
const TX_CHARACTERISTIC_UUID = '6e400002-b5a3-f393-e0a9-e50e24dcca9e';
const RX_CHARACTERISTIC_UUID = '6e400003-b5a3-f393-e0a9-e50e24dcca9e';

let device;
let txCharacteristic;

const passwordInput = document.getElementById('password');
const connectBtn = document.getElementById('connectBtn');
const sendBtn = document.getElementById('sendBtn');
const statusEl = document.getElementById('status');

function setStatus(message) {
  statusEl.textContent = `Status: ${message}`;
}

async function connectToDevice() {
  if (!window.isSecureContext) {
    setStatus('Open this page from localhost or HTTPS first.');
    return;
  }

  if (!navigator.bluetooth) {
    setStatus('Web Bluetooth is not supported in this browser.');
    return;
  }

  try {
    connectBtn.disabled = true;
    setStatus('Requesting BLE device...');

    device = await navigator.bluetooth.requestDevice({
      filters: [{ services: [UART_SERVICE_UUID] }],
      optionalServices: [UART_SERVICE_UUID]
    });

    await device.gatt.connect();

    const server = await device.gatt.getPrimaryService(UART_SERVICE_UUID);
    const tx = await server.getCharacteristic(TX_CHARACTERISTIC_UUID);
    const rx = await server.getCharacteristic(RX_CHARACTERISTIC_UUID);

    txCharacteristic = tx;

    if (device.gatt.connected) {
      setStatus(`Connected to ${device.name || 'BLE device'}`);
      sendBtn.disabled = false;
      connectBtn.textContent = 'Connected';
    }

    device.addEventListener('gattserverdisconnected', () => {
      setStatus('Disconnected from BLE device.');
      sendBtn.disabled = true;
      connectBtn.disabled = false;
      connectBtn.textContent = 'Connect BLE device';
    });

    if (rx.properties.notify) {
      await rx.startNotifications();
      rx.addEventListener('characteristicvaluechanged', (event) => {
        const value = new TextDecoder().decode(event.target.value);
        setStatus(`Received: ${value}`);
      });
    }
  } catch (error) {
    setStatus(`Connection failed: ${error.message || error}`);
    connectBtn.disabled = false;
  }
}

async function sendPassword() {
  const password = passwordInput.value.trim();
  if (!password) {
    setStatus('Please enter a password first.');
    return;
  }
  if (!device?.gatt?.connected || !txCharacteristic) {
    setStatus('Connect to a BLE device first.');
    return;
  }

  try {
    const encoder = new TextEncoder();
    const payload = encoder.encode(`${password}\n`);
    await txCharacteristic.writeValue(payload);
    setStatus('Password sent.');
  } catch (error) {
    setStatus(`Send failed: ${error.message || error}`);
  }
}

connectBtn.addEventListener('click', connectToDevice);
sendBtn.addEventListener('click', sendPassword);
sendBtn.disabled = true;
