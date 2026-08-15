$ErrorActionPreference = 'Stop'

$baseUrl = "http://localhost:8080/api/v1"
$headers = @{ "Content-Type" = "application/json" }

Write-Host "Creating Site..."
$site = Invoke-RestMethod -Method Post -Uri "$baseUrl/sites" -Headers $headers -Body '{"name":"HQ Campus","description":"Main Headquarters","location":{"address":"123 Innovation Way","city":"Techville","country":"USA","latitude":37.7749,"longitude":-122.4194},"status":"ACTIVE","contactInfo":{"name":"Admin","email":"admin@nexus.com"}}'
$siteId = $site.id

Write-Host "Creating Building..."
$building = Invoke-RestMethod -Method Post -Uri "$baseUrl/sites/$siteId/buildings" -Headers $headers -Body '{"name":"Building A","description":"Engineering Center","status":"ACTIVE"}'
$buildingId = $building.id

Write-Host "Creating Space..."
$space = Invoke-RestMethod -Method Post -Uri "$baseUrl/buildings/$buildingId/spaces" -Headers $headers -Body '{"name":"Server Room 1","description":"Primary Datacenter","spaceType":"DATACENTER","status":"ACTIVE"}'
$spaceId = $space.id

Write-Host "Creating Device..."
$device = Invoke-RestMethod -Method Post -Uri "$baseUrl/spaces/$spaceId/devices" -Headers $headers -Body "{`"name`":`"Rack 1 Temp Sensor`",`"deviceType`":`"TEMPERATURE_SENSOR`",`"manufacturer`":`"NexusTech`",`"model`":`"TS-1000`",`"serialNumber`":`"SN-$(New-Guid)`"}"
$deviceId = $device.id

Write-Host "Creating Rule..."
$rule = Invoke-RestMethod -Method Post -Uri "$baseUrl/rules" -Headers $headers -Body '{
  "name": "High Temperature Alert",
  "enabled": true,
  "priority": 1,
  "conditions": [
    { "field": "VALUE", "operator": "GT", "value": "30.0" }
  ],
  "actions": [
    { "actionType": "CREATE_ALERT", "parameters": { "severity": "CRITICAL", "message": "Temperature is dangerously high!" } }
  ],
  "metadata": {}
}'

Write-Host "Done! Seeded Site: $siteId, Building: $buildingId, Space: $spaceId, Device: $deviceId"
