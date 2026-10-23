export type AdminPhoto = {
  url: string;
  alt: string;
  credit: string;
  license: string;
};
export type AdminHotelDraft = {
  version: number | null;
  slug: string;
  name: string;
  city: string;
  cityCode: string;
  timeZone: string;
  address: string;
  description: string;
  distanceFromCityCenter: number;
  published: boolean;
  photos: AdminPhoto[];
};
export type AdminHotel = AdminHotelDraft & { id: string; version: number };
export type AdminRoomDraft = {
  version: number | null;
  name: string;
  number: number;
  capacity: number;
  nightlyPrice: string;
  description: string;
  published: boolean;
  photos: AdminPhoto[];
};
export type AdminRoom = AdminRoomDraft & {
  id: string;
  hotelId: string;
  version: number;
};
export type AdminPage<T> = {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
