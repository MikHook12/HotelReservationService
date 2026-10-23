import type { HotelCard } from "@/lib/types";

const LOCAL_MEDIA = /^\/media\/[A-Za-z0-9/_-]+\.[A-Za-z0-9]{1,12}$/;
export function PropertyPhoto({
  photos,
  fallback = "hotel",
  className = "hotel-art",
}: {
  photos: HotelCard["photos"];
  fallback?: "hotel" | "room";
  className?: string;
}) {
  const photo = photos.find(
    (candidate) =>
      LOCAL_MEDIA.test(candidate.url) &&
      !candidate.url.includes("..") &&
      !candidate.url.includes("//") &&
      candidate.alt.trim() &&
      candidate.credit.trim() &&
      candidate.license.trim(),
  );
  return (
    <figure className={className}>
      <img
        src={
          photo?.url ||
          (fallback === "room" ? "/photos/room.jpg" : "/photos/hotel-hero.jpg")
        }
        alt={
          photo?.alt ||
          "Пример гостиничного интерьера, не фотография этого отеля"
        }
        loading="lazy"
      />
      <figcaption className={photo ? "hotel-photo-credit" : "hotel-art-label"}>
        {photo
          ? `${photo.credit} · ${photo.license}`
          : "Пример интерьера · не фото отеля"}
      </figcaption>
    </figure>
  );
}
