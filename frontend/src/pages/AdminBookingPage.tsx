import { useEffect, useState } from 'react';
import { adminApi } from '../api/admin';
import { eventApi } from '../api/events';
import type { Booking, Event } from '../types';

const STATUS_LABELS: Record<string, { label: string; className: string }> = {
  CONFIRMED: { label: '확정', className: 'bg-green-100 text-green-700' },
  PENDING: { label: '대기', className: 'bg-yellow-100 text-yellow-700' },
  CANCELLED: { label: '취소', className: 'bg-red-100 text-red-700' },
};

export default function AdminBookingPage() {
  const [bookings, setBookings] = useState<Booking[]>([]);
  const [events, setEvents] = useState<Event[]>([]);
  const [selectedEventId, setSelectedEventId] = useState<number | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  useEffect(() => {
    // 이벤트 목록 로드 (필터용, 최대 100건)
    eventApi.getAll(0, 100).then((res) => setEvents(res.data.content)).catch(() => {});
  }, []);

  useEffect(() => {
    loadBookings(page);
  }, [page, selectedEventId]);

  const loadBookings = async (p: number) => {
    setLoading(true);
    try {
      const res = await adminApi.getAllBookings(p, 20, selectedEventId);
      setBookings(res.data.content);
      setTotalPages(res.data.totalPages);
      setTotalElements(res.data.totalElements);
    } catch {
      setError('예매 목록을 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const confirmedCount = bookings.filter((b) => b.status === 'CONFIRMED').length;
  const cancelledCount = bookings.filter((b) => b.status === 'CANCELLED').length;

  if (loading && page === 0) {
    return (
      <div className="flex justify-center items-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
      </div>
    );
  }

  return (
    <div className="max-w-7xl mx-auto px-4 py-8">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">예매 관리</h1>
        <div className="flex items-center gap-3">
          <select
            value={selectedEventId ?? ''}
            onChange={(e) => {
              setSelectedEventId(e.target.value ? Number(e.target.value) : undefined);
              setPage(0);
            }}
            className="px-3 py-2 text-sm border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="">전체 이벤트</option>
            {events.map((ev) => (
              <option key={ev.id} value={ev.id}>
                {ev.title}
              </option>
            ))}
          </select>
          <span className="text-sm text-gray-500">총 {totalElements}건</span>
        </div>
      </div>

      {error && (
        <div className="mb-4 p-3 bg-red-50 border border-red-200 rounded text-red-700 text-sm">
          {error}
        </div>
      )}

      {/* 요약 카드 */}
      <div className="grid grid-cols-3 gap-4 mb-6">
        <div className="bg-white rounded-lg shadow-sm p-4 border">
          <p className="text-sm text-gray-500">전체 예매</p>
          <p className="text-2xl font-bold text-gray-900 mt-1">{totalElements}</p>
        </div>
        <div className="bg-white rounded-lg shadow-sm p-4 border">
          <p className="text-sm text-gray-500">현재 페이지 확정</p>
          <p className="text-2xl font-bold text-green-600 mt-1">{confirmedCount}</p>
        </div>
        <div className="bg-white rounded-lg shadow-sm p-4 border">
          <p className="text-sm text-gray-500">현재 페이지 취소</p>
          <p className="text-2xl font-bold text-red-600 mt-1">{cancelledCount}</p>
        </div>
      </div>

      {/* 예매 테이블 */}
      <div className="bg-white rounded-lg shadow-md overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b">
            <tr>
              <th className="text-left px-4 py-3 font-medium text-gray-600">예매번호</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">예매자</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">공연명</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">좌석</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">금액</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">상태</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">예매일시</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {loading ? (
              <tr>
                <td colSpan={7} className="text-center py-12 text-gray-400">
                  로딩 중...
                </td>
              </tr>
            ) : bookings.length === 0 ? (
              <tr>
                <td colSpan={7} className="text-center py-12 text-gray-500">
                  예매 내역이 없습니다.
                </td>
              </tr>
            ) : (
              bookings.map((booking) => {
                const statusInfo = STATUS_LABELS[booking.status] ?? {
                  label: booking.status,
                  className: 'bg-gray-100 text-gray-700',
                };
                return (
                  <tr key={booking.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-mono text-xs text-gray-700">
                      {booking.bookingNo}
                    </td>
                    <td className="px-4 py-3 text-gray-700">
                      {booking.userNickname ?? '-'}
                    </td>
                    <td className="px-4 py-3 font-medium text-gray-900">
                      {booking.eventTitle}
                    </td>
                    <td className="px-4 py-3 text-gray-600">
                      {booking.section}-{booking.seatRow}{booking.seatNumber}
                    </td>
                    <td className="px-4 py-3 text-gray-700">
                      {booking.price?.toLocaleString()}원
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block px-2 py-0.5 rounded-full text-xs font-medium ${statusInfo.className}`}
                      >
                        {statusInfo.label}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-500 text-xs">
                      {new Date(booking.createdAt).toLocaleString('ko-KR', {
                        month: 'short',
                        day: 'numeric',
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* 페이지네이션 */}
      {totalPages > 1 && (
        <div className="flex justify-center items-center gap-2 mt-6">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            className="px-3 py-1 text-sm border rounded hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            이전
          </button>
          <span className="text-sm text-gray-600">
            {page + 1} / {totalPages}
          </span>
          <button
            onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1}
            className="px-3 py-1 text-sm border rounded hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            다음
          </button>
        </div>
      )}
    </div>
  );
}
