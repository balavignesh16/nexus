CREATE TABLE spaces (
    id UUID PRIMARY KEY,
    building_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_spaces_building
        FOREIGN KEY (building_id)
        REFERENCES buildings(id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_spaces_building_id ON spaces(building_id);
