# NEXUS API Documentation

## Sites API

The `Site` represents the highest-level physical deployment location (e.g., Campus, Plant, Facility).

### Object Schema
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "name": "VIT Chennai Campus",
  "description": "Main academic and administrative blocks.",
  "createdAt": "2026-07-27T10:00:00Z",
  "updatedAt": "2026-07-27T10:00:00Z"
}
```

### Endpoints

- `POST /api/v1/sites` - Create a new Site
- `GET /api/v1/sites` - List all Sites
- `GET /api/v1/sites/{id}` - Get a Site by ID
- `PUT /api/v1/sites/{id}` - Update a Site entirely
- `DELETE /api/v1/sites/{id}` - Delete a Site (will return 409 Conflict if Buildings exist)

## Buildings API

A `Building` represents a physical structure belonging to exactly one `Site`.

### Object Schema
```json
{
  "id": "223e4567-e89b-12d3-a456-426614174000",
  "siteId": "123e4567-e89b-12d3-a456-426614174000",
  "name": "Academic Block 1",
  "description": "Primary teaching facility.",
  "createdAt": "2026-07-27T10:00:00Z",
  "updatedAt": "2026-07-27T10:00:00Z"
}
```

### Endpoints

- `POST /api/v1/sites/{siteId}/buildings` - Create a new Building under a Site
- `GET /api/v1/sites/{siteId}/buildings` - List all Buildings for a Site
- `GET /api/v1/buildings/{buildingId}` - Get a Building by ID
- `PUT /api/v1/buildings/{buildingId}` - Update a Building entirely
- `DELETE /api/v1/buildings/{buildingId}` - Delete a Building (will return 409 Conflict if Spaces exist)

## Spaces API

A `Space` represents a physical area belonging to exactly one `Building`.

### Object Schema
```json
{
  "id": "334f5678-f90c-23e4-b567-537725285000",
  "buildingId": "223e4567-e89b-12d3-a456-426614174000",
  "name": "Laboratory 101",
  "description": "Main physics lab.",
  "createdAt": "2026-07-27T10:00:00Z",
  "updatedAt": "2026-07-27T10:00:00Z"
}
```

### Endpoints

- `POST /api/v1/buildings/{buildingId}/spaces` - Create a new Space under a Building
- `GET /api/v1/buildings/{buildingId}/spaces` - List all Spaces for a Building
- `GET /api/v1/spaces/{spaceId}` - Get a Space by ID
- `PUT /api/v1/spaces/{spaceId}` - Update a Space entirely
- `DELETE /api/v1/spaces/{spaceId}` - Delete a Space

## Devices API

A `Device` represents a physical IoT device belonging to exactly one `Space`.

### Object Schema
```json
{
  "id": "445g6789-g01d-34f5-c678-648836396111",
  "spaceId": "334f5678-f90c-23e4-b567-537725285000",
  "name": "Temperature Sensor 1",
  "deviceType": "TEMPERATURE_SENSOR",
  "manufacturer": "Acme Corp",
  "model": "T-100",
  "serialNumber": "SN-12345",
  "status": "REGISTERED",
  "description": "Main lab sensor.",
  "createdAt": "2026-07-27T10:00:00Z",
  "updatedAt": "2026-07-27T10:00:00Z",
  "createdBy": "system",
  "updatedBy": "system"
}
```

### Endpoints

- `POST /api/v1/spaces/{spaceId}/devices` - Create a new Device under a Space
- `GET /api/v1/spaces/{spaceId}/devices` - List all Devices for a Space
- `GET /api/v1/devices/{deviceId}` - Get a Device by ID
- `PUT /api/v1/devices/{deviceId}` - Update a Device entirely
- `DELETE /api/v1/devices/{deviceId}` - Delete a Device
