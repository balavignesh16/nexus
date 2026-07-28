export interface Site {
  id: string;
  name: string;
  description?: string;
  createdAt: string;
  updatedAt: string;
}
export interface CreateSiteRequest {
  name: string;
  description?: string;
}
export interface UpdateSiteRequest {
  name: string;
  description?: string;
}

export interface Building {
  id: string;
  name: string;
  description?: string;
  siteId: string;
  createdAt: string;
  updatedAt: string;
}
export interface CreateBuildingRequest {
  name: string;
  description?: string;
}
export interface UpdateBuildingRequest {
  name: string;
  description?: string;
}

export interface Space {
  id: string;
  name: string;
  description?: string;
  buildingId: string;
  createdAt: string;
  updatedAt: string;
}
export interface CreateSpaceRequest {
  name: string;
  description?: string;
}
export interface UpdateSpaceRequest {
  name: string;
  description?: string;
}
