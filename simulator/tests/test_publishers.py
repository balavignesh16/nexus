from unittest.mock import patch, MagicMock
from nexus_simulator.publishers import HTTPPublisher

def test_http_publisher():
    publisher = HTTPPublisher(backend_url="http://localhost:8080/api/v1")
    
    with patch("urllib.request.urlopen") as mock_urlopen:
        mock_response = MagicMock()
        mock_response.status = 201
        # urlopen is used as a context manager (with urlopen(...) as response)
        mock_urlopen.return_value.__enter__.return_value = mock_response
        
        publisher.publish(device_id="123", sensor_type="TEMP", value=22.5, unit="C")
        
        mock_urlopen.assert_called_once()
        args, kwargs = mock_urlopen.call_args
        request = args[0]
        
        assert request.full_url == "http://localhost:8080/api/v1/telemetry"
        assert request.headers['Content-type'] == 'application/json'
        # Can verify payload by loading request.data
        import json
        payload = json.loads(request.data)
        assert payload["deviceId"] == "123"
        assert payload["value"] == 22.5
