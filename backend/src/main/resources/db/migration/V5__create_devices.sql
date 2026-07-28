CREATE TABLE devices (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    device_type VARCHAR(50) NOT NULL,
    manufacturer VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    serial_number VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL DEFAULT 'system',
    updated_by VARCHAR(100) NOT NULL DEFAULT 'system',
    CONSTRAINT fk_device_space FOREIGN KEY (space_id) REFERENCES spaces (id) ON DELETE RESTRICT
);

CREATE INDEX idx_devices_space_id ON devices(space_id);