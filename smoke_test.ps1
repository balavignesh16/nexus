$site = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/sites" -Method Post -ContentType "application/json" -Body '{"name":"Smoke Test Site","description":"Smoke Test"}'
$building = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/sites/$($site.id)/buildings" -Method Post -ContentType "application/json" -Body '{"name":"Smoke Test Building","description":"Smoke Test"}'
$space = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/buildings/$($building.id)/spaces" -Method Post -ContentType "application/json" -Body '{"name":"Smoke Test Space","description":"Smoke Test"}'
$device = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/spaces/$($space.id)/devices" -Method Post -ContentType "application/json" -Body '{"name":"Smoke Test Device","deviceType":"TEMPERATURE_SENSOR","manufacturer":"Acme","model":"v1","serialNumber":"SMOKE-123","description":"Smoke Test"}'

Write-Output "Created Device ID: $($device.id)"

$deviceGet = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/devices/$($device.id)" -Method Get
Write-Output "Retrieved Device Serial: $($deviceGet.serialNumber)"

# Try to delete space with device
try {
    Invoke-RestMethod -Uri "http://localhost:8080/api/v1/spaces/$($space.id)" -Method Delete
    Write-Output "ERROR: Should not be able to delete space with device"
} catch {
    Write-Output "SUCCESS: Space deletion rejected (expected)"
}

Invoke-RestMethod -Uri "http://localhost:8080/api/v1/devices/$($device.id)" -Method Delete
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/spaces/$($space.id)" -Method Delete
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/buildings/$($building.id)" -Method Delete
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/sites/$($site.id)" -Method Delete

Write-Output "Smoke test completed successfully."