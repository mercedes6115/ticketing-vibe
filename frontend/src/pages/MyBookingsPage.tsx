import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { bookingApi } from '../api/bookings';
import { useUserStore } from '../stores/userStore';
import type { Booking } from '../types';

export default function MyBookingsPage() {
  const { user } = useUserStore();
  const navigate = useNavigate();
  const [bookings, setBookings] = useState<Booking[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!user) {
      navigate('/login');
      return;
    }
    loadBookings();
  }, [user]);

  const loadBookings = async () => {
    try {
      const res = await bookingApi.getMyBookings();
      setBookings(res.data.content);
    } catch (error) {
      console.error('Failed to load bookings:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = async (bookingId: number) => {
    if (!confirm('예매를 취소하시겠습니까?')) return;

    try {
      await bookingApi.cancel(bookingId);
      loadBookings();
    } catch (error: any) {
      alert(error.response?.data?.message || '취소에 실패했습니다.');
    }
  };

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString('ko-KR', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const getStatusBadge = (status: Booking['status']) => {
    const styles = {
      PENDING: 'bg-yellow-100 text-yellow-800',
      CONFIRMED: 'bg-green-100 text-green-800',
      CANCELLED: 'bg-red-100 text-red-800',
    };
    const labels = {
      PENDING: '대기 중',
      CONFIRMED: '예매 완료',
      CANCELLED: '취소됨',
    };
    return (
      <span className={`px-2 py-1 rounded-full text-xs font-medium ${styles[status]}`}>
        {labels[status]}
      </span>
    );
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">내 예매 내역</h1>

      {bookings.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-gray-500 mb-4">예매 내역이 없습니다.</p>
          <Link
            to="/"
            className="inline-block px-4 py-2 bg-blue-600 text-white rounded-lg"
          >
            공연 둘러보기
          </Link>
        </div>
      ) : (
        <div className="space-y-4">
          {bookings.map((booking) => (
            <div
              key={booking.id}
              className="bg-white rounded-lg shadow-md p-6"
            >
              <div className="flex justify-between items-start mb-4">
                <div>
                  <p className="text-sm text-gray-500">예매번호: {booking.bookingNo}</p>
                  <h3 className="font-semibold text-lg">{booking.eventTitle}</h3>
                </div>
                {getStatusBadge(booking.status)}
              </div>

              <div className="grid grid-cols-2 gap-4 text-sm mb-4">
                <div>
                  <p className="text-gray-500">일시</p>
                  <p className="font-medium">{formatDate(booking.eventStartAt)}</p>
                </div>
                <div>
                  <p className="text-gray-500">좌석</p>
                  <p className="font-medium">
                    {booking.section} {booking.seatRow}열 {booking.seatNumber}번
                  </p>
                </div>
                <div>
                  <p className="text-gray-500">결제 금액</p>
                  <p className="font-medium">{booking.price?.toLocaleString()}원</p>
                </div>
                <div>
                  <p className="text-gray-500">예매일</p>
                  <p className="font-medium">{formatDate(booking.createdAt)}</p>
                </div>
              </div>

              {booking.status === 'CONFIRMED' && (
                <div className="flex justify-end">
                  <button
                    onClick={() => handleCancel(booking.id)}
                    className="px-4 py-2 text-red-600 border border-red-600 rounded-lg hover:bg-red-50 transition"
                  >
                    예매 취소
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
