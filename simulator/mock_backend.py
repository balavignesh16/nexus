from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import uuid
import time
import threading

class MockBackendHandler(BaseHTTPRequestHandler):
    devices = []
    
    # Metrics
    telemetry_received = 0
    start_time = time.time()
    
    @classmethod
    def setup_devices(cls):
        if not cls.devices:
            for _ in range(100):
                cls.devices.append({"id": str(uuid.uuid4()), "deviceType": "TEMPERATURE_SENSOR", "status": "ACTIVE"})
            for _ in range(50):
                cls.devices.append({"id": str(uuid.uuid4()), "deviceType": "GAS_SENSOR", "status": "ACTIVE"})
            for _ in range(25):
                cls.devices.append({"id": str(uuid.uuid4()), "deviceType": "MOTION_SENSOR", "status": "ACTIVE"})

    def do_GET(self):
        if self.path == '/api/v1/devices/active':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(self.devices).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path == '/api/v1/telemetry':
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            
            MockBackendHandler.telemetry_received += 1
            if MockBackendHandler.telemetry_received % 100 == 0:
                elapsed = time.time() - MockBackendHandler.start_time
                print(f"Backend received {MockBackendHandler.telemetry_received} telemetry records in {elapsed:.1f} seconds")
                
            self.send_response(201)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()
            
    def log_message(self, format, *args):
        # Suppress logging to avoid console spam
        pass

def run():
    MockBackendHandler.setup_devices()
    server_address = ('', 8080)
    httpd = HTTPServer(server_address, MockBackendHandler)
    print("Mock backend listening on port 8080...")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    httpd.server_close()

if __name__ == '__main__':
    run()
