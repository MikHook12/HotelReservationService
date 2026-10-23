import { HotelEditor } from "../../hotel-editor";
export default async function EditHotelPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <HotelEditor hotelId={id} />;
}
