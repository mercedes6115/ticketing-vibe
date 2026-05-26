import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { eventApi } from '../api/events';
import { queueApi } from '../api/queue';
import { useUserStore } from '../stores/userStore';
import { useQueueStore } from '../stores/queueStore';
import type { Event } from '../types';

export default function EventDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useUserStore();
  const { setIsInQueue } = useQueueStore();
  const [event, setEvent] = useState<Event | null>(null);
  const [loading, setLoading] = useState(true);
  const [joining, setJoining] = useState(false);

  useEffect(() => {
    if (id) loadEvent(Number(id));
  }, [id]);

  const loadEvent = async (eventId: number) => {
    try {
      const res = await eventApi.getById(eventId);
      setEvent(res.data);
    } catch (error) {
      console.error('Failed to load event:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleBooking = async () => {
    if (!event) return;

    if (!user) {
      navigate('/login');
      return;
    }

    if (event.status !== 'OPEN') {
      alert('예매 가능한 상태가 아닙니다.');
      return;
    }

    setJoining(true);
    try {
      await queueApi.enter(event.id);
      setIsInQueue(true);
      navigate(`/events/${event.id}/queue`);
    } catch (error: any) {
      alert(error.response?.data?.message || '대기열 진입에 실패했습니다.');
    } finally {
      setJoining(false);
    }
  };

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

  if (loading) {
    return (
      <div className="flex justify-center items-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
      </div>
    );
  }

  if (!event) {
    return (
      <div className="text-center py-12 text-gray-500">
        이벤트를 찾을 수 없습니다.
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      {/* 헤더 이미지 */}
      <div className="h-64 bg-gradient-to-br from-blue-500 to-purple-600 rounded-lg flex items-center justify-center mb-6 overflow-hidden">
        {event.imageUrl ? (
          <img
            src={`http://localhost:8080${event.imageUrl}`}
            alt={event.title}
            className="w-full h-full object-cover rounded-lg"
          />
        ) : (
          <span className="text-white text-6xl">🎫</span>
        )}
      </div>

      {/* 이벤트 정보 */}
      <div className="bg-white rounded-lg shadow-md p-6 mb-6">
        <div className="flex justify-between items-start mb-4">
          <h1 className="text-2xl font-bold text-gray-900">{event.title}</h1>
          <span
            className={`px-3 py-1 rounded-full text-sm font-medium ${
              event.status === 'OPEN'
                ? 'bg-green-100 text-green-800'
                : 'bg-gray-100 text-gray-800'
            }`}
          >
            {event.status === 'OPEN' ? '예매 중' : event.status}
          </span>
        </div>

        <div className="space-y-3 text-gray-600">
          <div className="flex items-center gap-2">
            <span>📍</span>
            <span>{event.venue}</span>
          </div>
          <div className="flex items-center gap-2">
            <span>📅</span>
            <span>{formatDate(event.startAt)}</span>
          </div>
          <div className="flex items-center gap-2">
            <span>🎟️</span>
            <span>
              잔여 좌석: <strong>{event.availableSeats}</strong> / {event.totalSeats}석
            </span>
          </div>
        </div>

        {event.description && (
          <div className="mt-6 pt-6 border-t">
            <h2 className="font-semibold text-gray-900 mb-2">공연 소개</h2>
            <p className="text-gray-600 whitespace-pre-line">{event.description}</p>
          </div>
        )}
      </div>

      {/* 예매 버튼 */}
      <div className="bg-white rounded-lg shadow-md p-6">
        <div className="flex justify-between items-center">
          <div>
            <p className="text-sm text-gray-500">예매 오픈</p>
            <p className="font-medium">{formatDate(event.openAt)}</p>
          </div>
          <button
            onClick={handleBooking}
            disabled={event.status !== 'OPEN' || event.availableSeats === 0 || joining}
            className={`px-8 py-3 rounded-lg font-semibold text-white transition ${
              event.status === 'OPEN' && event.availableSeats > 0
                ? 'bg-blue-600 hover:bg-blue-700'
                : 'bg-gray-400 cursor-not-allowed'
            }`}
          >
            {joining ? '대기열 진입 중...' : '예매하기'}
          </button>
        </div>
      </div>
    </div>
  );
}
