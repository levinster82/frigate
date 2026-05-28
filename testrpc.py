import socket, json, ssl, argparse

# Constants
SCAN_PRIVATE_KEY = "0f694e068028a717f8af6b9411f9a133dd3565258714cc226594b34db90c1f2c"
SPEND_PUBLIC_KEY = "025cc9856d6f8375350e123978daac200c260cb5b5ae83106cab90484dcd8fcf36"
START_HEIGHT = 709632
EXPECTED_ADDRESS = "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"
SOURCE_URL = "https://github.com/bitcoin/bips/blob/master/bip-0352/send_and_receive_test_vectors.json"
SSL_PORT, TCP_PORT = 57002, 57001

parser = argparse.ArgumentParser(description='Test Frigate Silent Payments RPC with SSL/TCP')
parser.add_argument('--host', default='127.0.0.1', help='Frigate server host (default: 127.0.0.1)')
parser.add_argument('--port', type=int, default=SSL_PORT, help=f'Frigate server port (default: {SSL_PORT} for SSL, {TCP_PORT} for plain TCP)')
parser.add_argument('--plain-tcp', action='store_true', help='Use plain TCP instead of SSL (default: SSL)')
parser.add_argument('--verify-cert', action='store_true', help='Verify SSL certificate when using SSL (default: disabled for testing)')
args = parser.parse_args()

# Connection setup
is_tcp = args.plain_tcp
conn_type = "TCP" if is_tcp else "SSL"
port = TCP_PORT if (args.port == SSL_PORT and is_tcp) else args.port

def create_connection():
    """Create SSL or TCP connection based on args"""
    try:
        if is_tcp:
            s = socket.create_connection((args.host, port))
            print(f"✅ Plain TCP connection established")
            return s
        else:
            context = ssl.create_default_context()
            if not args.verify_cert:
                context.check_hostname = False
                context.verify_mode = ssl.CERT_NONE

            s = context.wrap_socket(socket.create_connection((args.host, port)), server_hostname=args.host)
            cipher = s.cipher()[0] if s.cipher() else 'unknown'
            print(f"✅ SSL connection established (cipher: {cipher})")
            return s
    except (ssl.SSLError, Exception) as e:
        print(f"❌ {conn_type} connection failed: {e}")
        exit(1)

s = create_connection()

req = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "blockchain.silentpayments.subscribe",
    "params": [SCAN_PRIVATE_KEY, SPEND_PUBLIC_KEY, START_HEIGHT]
}

def print_test_info():
    """Print test information header"""
    print(f"=== BIP 352 Silent Payments {conn_type} Test ===")
    print(f"Testing: blockchain.silentpayments.subscribe RPC method over {'plain TCP' if is_tcp else 'SSL'}")
    print("Validates: Silent Payments address generation from scan+spend keys")
    print(f"Test Vector Source: {SOURCE_URL}")
    print(f"Connecting to: {args.host}:{port} ({conn_type})")
    if not is_tcp:
        print(f"Certificate verification: {'enabled' if args.verify_cert else 'disabled'}")
    print(f"Scan Private Key:  {SCAN_PRIVATE_KEY}")
    print(f"Spend Public Key:  {SPEND_PUBLIC_KEY}")
    print(f"Start Height:      {START_HEIGHT}")
    print(f"Expected Address:  {EXPECTED_ADDRESS}")
    print("=" * 70)

print_test_info()

def validate_response(response):
    """Validate response against expected BIP 352 test vector"""
    try:
        data = json.loads(response)
        if "result" in data:
            actual = data["result"]
            if actual == EXPECTED_ADDRESS:
                print("✅ SUCCESS: Response matches expected BIP 352 test vector!")
            else:
                print("❌ FAILURE: Response does not match expected address")
                print(f"Expected: {EXPECTED_ADDRESS}")
                print(f"Actual:   {actual}")
        else:
            print("❌ ERROR: No result field in response")
            if "error" in data:
                print(f"Server error: {data['error']}")
    except json.JSONDecodeError:
        print("❌ ERROR: Invalid JSON response")
    except Exception as e:
        print(f"❌ ERROR: {e}")

# Send request and validate response
s.send((json.dumps(req) + "\n").encode())
resp = s.recv(4096).decode()
print("Response:", resp)
validate_response(resp)
s.close()

