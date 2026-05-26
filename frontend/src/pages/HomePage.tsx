import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { eventApi } from '../api/events';
import type { Event } from '../types';

export default function HomePage() {
  const [events, setEvents] = useState<Event[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadEvents();
  }, []);

  const loadEvents = async () => {
    try {
      const res = await eventApi.getAll();
      setEvents(res.data.content);
    } catch (error) {
      console.error('Failed to load events:', error);
    } finally {
      setLoading(false);
    }
  };

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString('ko-KR', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const getStatusBadge = (status: Event['status']) => {
    const styles = {
      SCHEDULED: 'bg-yellow-100 text-yellow-800',
      OPEN: 'bg-green-100 text-green-800',
      CLOSED: 'bg-gray-100 text-gray-800',
      CANCELLED: 'bg-red-100 text-red-800',
    };
    const labels = {
      SCHEDULED: '예매 예정',
      OPEN: '예매 중',
      CLOSED: '예매 마감',
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
    <div className="max-w-6xl mx-auto px-4 py-8">
      <h1 className="text-3xl font-bold text-gray-900 mb-8">공연 / 이벤트</h1>

      {events.length === 0 ? (
        <div className="text-center py-12 text-gray-500">
          등록된 이벤트가 없습니다.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {events.map((event) => (
            <Link
              key={event.id}
              to={`/events/${event.id}`}
              className="bg-white rounded-lg shadow-md overflow-hidden hover:shadow-lg transition-shadow"
            >
              <div className="h-48 bg-gradient-to-br from-blue-500 to-purple-600 flex items-center justify-center overflow-hidden">
                {event.imageUrl ? (
                  <img
                    src={`http://localhost:8080${event.imageUrl}`}
                    alt={event.title}
                    className="w-full h-full object-cover"
                  />
                ) : (
                  <span className="text-white text-4xl">🎫</span>
                )}
              </div>
              <div className="p-4">
                <div className="flex justify-between items-start mb-2">
                  <h2 className="text-lg font-semibold text-gray-900 line-clamp-1">
                    {event.title}
                  </h2>
                  {getStatusBadge(event.status)}
                </div>
                <p className="text-sm text-gray-600 mb-2">{event.venue}</p>
                <p className="text-sm text-gray-500 mb-3">
                  {formatDate(event.startAt)}
                </p>
                <div className="flex justify-between items-center text-sm">
                  <span className="text-gray-500">
                    잔여 {event.availableSeats} / {event.totalSeats}석
                  </span>
                  {event.status === 'OPEN' && (
                    <span className="text-blue-600 font-medium">예매하기 →</span>
                  )}
                </div>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
