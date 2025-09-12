import socket, json, ssl, argparse

parser = argparse.ArgumentParser(description='Test Frigate Silent Payments RPC')
parser.add_argument('--host', default='127.0.0.1', help='Frigate server host (default: 127.0.0.1)')
parser.add_argument('--port', type=int, default=57001, help='Frigate server port (default: 57001)')
args = parser.parse_args()

HOST = args.host
PORT = args.port

# for plain tcp socket
s = socket.create_connection((HOST, PORT))

# for ssl socket
# s = ssl.wrap_socket(socket.create_connection((HOST, PORT)))

req = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "blockchain.silentpayments.subscribe",
    "params": [
        "0f694e068028a717f8af6b9411f9a133dd3565258714cc226594b34db90c1f2c",  # Official BIP 352 test scan private key
        "025cc9856d6f8375350e123978daac200c260cb5b5ae83106cab90484dcd8fcf36",  # Official BIP 352 test spend public key
        709632
    ]
}

# Test vectors from official BIP 352 specification
# Source: https://github.com/bitcoin/bips/blob/master/bip-0352/send_and_receive_test_vectors.json
print("=== BIP 352 Silent Payments Test ===")
print("Testing: blockchain.silentpayments.subscribe RPC method")
print("Validates: Silent Payments address generation from scan+spend keys")
print(f"Test Vector Source: https://github.com/bitcoin/bips/blob/master/bip-0352/send_and_receive_test_vectors.json")
print(f"Connecting to: {HOST}:{PORT}")
print(f"Scan Private Key:  {req['params'][0]}")
print(f"Spend Public Key:  {req['params'][1]}")
print(f"Start Height:      {req['params'][2]}")
print(f"Expected Address:  sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv")
print("=" * 70)

s.send((json.dumps(req) + "\n").encode())
resp = s.recv(4096).decode()
print("Response:", resp)

# Parse and validate response
expected_address = "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"

try:
    response_data = json.loads(resp)
    if "result" in response_data:
        actual_address = response_data["result"]
        if actual_address == expected_address:
            print("✅ SUCCESS: Response matches expected BIP 352 test vector!")
        else:
            print("❌ FAILURE: Response does not match expected address")
            print(f"Expected: {expected_address}")
            print(f"Actual:   {actual_address}")
    else:
        print("❌ ERROR: No result field in response")
        if "error" in response_data:
            print(f"Server error: {response_data['error']}")
except json.JSONDecodeError:
    print("❌ ERROR: Invalid JSON response")
except Exception as e:
    print(f"❌ ERROR: {e}")

s.close()

