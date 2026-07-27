CREATE TABLE buildings (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_buildings_site FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE RESTRICT
);

CREATE INDEX idx_buildings_site_id ON buildings(site_id);
