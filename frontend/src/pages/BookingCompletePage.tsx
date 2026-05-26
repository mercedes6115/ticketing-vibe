import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { bookingApi } from '../api/bookings';
import type { Booking } from '../types';

export default function BookingCompletePage() {
  const { id: bookingNo } = useParams<{ id: string }>();
  const [booking, setBooking] = useState<Booking | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!bookingNo) {
      setError(true);
      setLoading(false);
      return;
    }
    bookingApi.getByNo(bookingNo)
      .then((res) => setBooking(res.data))
      .catch(() => setError(true))
      .finally(() => setLoading(false));
  }, [bookingNo]);

  if (loading) {
    return (
      <div className="max-w-lg mx-auto px-4 py-8 text-center">
        <p className="text-gray-500">예매 정보를 불러오는 중...</p>
      </div>
    );
  }

  if (error || !booking) {
    return (
      <div className="max-w-lg mx-auto px-4 py-8 text-center">
        <p className="text-gray-500">예매 정보를 찾을 수 없습니다.</p>
        <Link to="/" className="mt-4 inline-block px-4 py-2 bg-blue-600 text-white rounded-lg">
          홈으로 돌아가기
        </Link>
      </div>
    );
  }

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString('ko-KR', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
      weekday: 'long',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  return (
    <div className="max-w-lg mx-auto px-4 py-8">
      {/* 성공 아이콘 */}
      <div className="text-center mb-8">
        <div className="inline-flex items-center justify-center w-20 h-20 bg-green-100 rounded-full mb-4">
          <span className="text-4xl">✓</span>
        </div>
        <h1 className="text-2xl font-bold text-gray-900">예매 완료!</h1>
        <p className="text-gray-600 mt-2">예매가 성공적으로 완료되었습니다.</p>
      </div>

      {/* 예매 정보 */}
      <div className="bg-white rounded-lg shadow-md overflow-hidden mb-6">
        {/* 예매 번호 */}
        <div className="bg-blue-600 text-white p-4 text-center">
          <p className="text-sm opacity-80">예매 번호</p>
          <p className="text-2xl font-bold tracking-wider">{booking.bookingNo}</p>
        </div>

        {/* 상세 정보 */}
        <div className="p-6 space-y-4">
          <div>
            <p className="text-sm text-gray-500">공연명</p>
            <p className="font-semibold">{booking.eventTitle}</p>
          </div>
          <div>
            <p className="text-sm text-gray-500">일시</p>
            <p className="font-medium">{formatDate(booking.eventStartAt)}</p>
          </div>
          <div>
            <p className="text-sm text-gray-500">좌석</p>
            <p className="font-medium">
              {booking.section} {booking.seatRow}열 {booking.seatNumber}번
            </p>
          </div>
          <div className="pt-4 border-t">
            <div className="flex justify-between items-center">
              <span className="text-gray-500">결제 금액</span>
              <span className="text-xl font-bold text-blue-600">
                {booking.price?.toLocaleString()}원
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* QR 코드 (Mock) */}
      <div className="bg-white rounded-lg shadow-md p-6 text-center mb-6">
        <p className="text-sm text-gray-500 mb-3">입장 QR 코드</p>
        <div className="inline-block p-4 bg-gray-100 rounded-lg">
          <div className="w-32 h-32 bg-gray-300 flex items-center justify-center text-gray-500 text-xs">
            QR Code
          </div>
        </div>
        <p className="mt-3 text-xs text-gray-500">
          입장 시 QR 코드를 제시해주세요.
        </p>
      </div>

      {/* 버튼들 */}
      <div className="space-y-3">
        <Link
          to="/my/bookings"
          className="block w-full py-3 bg-blue-600 text-white text-center rounded-lg font-semibold hover:bg-blue-700 transition"
        >
          예매 내역 보기
        </Link>
        <Link
          to="/"
          className="block w-full py-3 bg-gray-200 text-gray-700 text-center rounded-lg font-semibold hover:bg-gray-300 transition"
        >
          홈으로 돌아가기
        </Link>
      </div>
    </div>
  );
}
