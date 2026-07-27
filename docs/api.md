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
- `DELETE /api/v1/buildings/{buildingId}` - Delete a Building
