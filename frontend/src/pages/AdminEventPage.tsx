import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { eventApi } from '../api/events';
import { adminApi, type EventCreateRequest } from '../api/admin';
import type { Event } from '../types';

const EMPTY_FORM: EventCreateRequest & { seatSection: string; seatRowCount: number; seatsPerRow: number; seatPrice: number } = {
  title: '',
  description: '',
  venue: '',
  startAt: '',
  openAt: '',
  totalSeats: 0,
  seatSection: 'A',
  seatRowCount: 5,
  seatsPerRow: 10,
  seatPrice: 50000,
};

export default function AdminEventPage() {
  const navigate = useNavigate();
  const [events, setEvents] = useState<Event[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [imageFile, setImageFile] = useState<File | null>(null);
  const [imagePreview, setImagePreview] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadEvents();
  }, []);

  const loadEvents = async () => {
    try {
      const res = await eventApi.getAll();
      setEvents(res.data.content);
    } catch {
      setError('이벤트 목록을 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
    const { name, value } = e.target;
    setForm((prev) => ({
      ...prev,
      [name]: ['totalSeats', 'seatRowCount', 'seatsPerRow', 'seatPrice'].includes(name)
        ? Number(value)
        : value,
    }));
  };

  const handleImageChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImageFile(file);
    setImagePreview(URL.createObjectURL(file));
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);

    try {
      const eventRes = await adminApi.createEvent({
        title: form.title,
        description: form.description,
        venue: form.venue,
        startAt: new Date(form.startAt).toISOString(),
        openAt: new Date(form.openAt).toISOString(),
        totalSeats: form.seatRowCount * form.seatsPerRow,
      });

      const eventId = eventRes.data.id;

      // 이미지 업로드 (선택한 경우)
      if (imageFile) {
        await adminApi.uploadEventImage(eventId, imageFile);
      }

      // 좌석 일괄 생성
      await adminApi.createSeats(eventId, {
        section: form.seatSection,
        rowCount: form.seatRowCount,
        seatsPerRow: form.seatsPerRow,
        price: form.seatPrice,
      });

      setShowForm(false);
      setForm(EMPTY_FORM);
      setImageFile(null);
      setImagePreview(null);
      await loadEvents();
    } catch (err: any) {
      setError(err.response?.data?.message || '이벤트 생성에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  const handleStatusChange = async (id: number, status: string) => {
    try {
      await adminApi.updateEventStatus(id, status);
      await loadEvents();
    } catch (err: any) {
      alert(err.response?.data?.message || '상태 변경에 실패했습니다.');
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm('이벤트를 삭제하시겠습니까?')) return;
    try {
      await adminApi.deleteEvent(id);
      await loadEvents();
    } catch (err: any) {
      alert(err.response?.data?.message || '삭제에 실패했습니다.');
    }
  };

  const statusLabels: Record<string, string> = {
    SCHEDULED: '예매 예정',
    OPEN: '예매 중',
    CLOSED: '예매 마감',
    CANCELLED: '취소됨',
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto px-4 py-8">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">이벤트 관리</h1>
        <button
          onClick={() => {
            setShowForm(!showForm);
            if (showForm) {
              setForm(EMPTY_FORM);
              setImageFile(null);
              setImagePreview(null);
            }
          }}
          className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition"
        >
          {showForm ? '닫기' : '+ 이벤트 등록'}
        </button>
      </div>

      {error && (
        <div className="mb-4 p-3 bg-red-50 border border-red-200 rounded text-red-700 text-sm">
          {error}
        </div>
      )}

      {/* 이벤트 등록 폼 */}
      {showForm && (
        <div className="bg-white rounded-lg shadow-md p-6 mb-6">
          <h2 className="text-lg font-semibold mb-4">새 이벤트 등록</h2>
          <form onSubmit={handleCreate} className="grid grid-cols-2 gap-4">
            <div className="col-span-2">
              <label className="block text-sm font-medium text-gray-700 mb-1">공연명</label>
              <input
                name="title"
                value={form.title}
                onChange={handleChange}
                required
                className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">장소</label>
              <input
                name="venue"
                value={form.venue}
                onChange={handleChange}
                required
                className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">공연 일시</label>
              <input
                type="datetime-local"
                name="startAt"
                value={form.startAt}
                onChange={handleChange}
                required
                className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">예매 오픈 일시</label>
              <input
                type="datetime-local"
                name="openAt"
                value={form.openAt}
                onChange={handleChange}
                required
                className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">공연 소개</label>
              <textarea
                name="description"
                value={form.description}
                onChange={handleChange}
                rows={2}
                className="w-full px-3 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>

            <div className="col-span-2">
              <label className="block text-sm font-medium text-gray-700 mb-1">포스터 이미지 (선택)</label>
              <div className="flex items-start gap-4">
                <div>
                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    className="px-3 py-2 border border-gray-300 rounded-lg text-sm text-gray-700 hover:bg-gray-50 transition"
                  >
                    파일 선택
                  </button>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/*"
                    onChange={handleImageChange}
                    className="hidden"
                  />
                  {imageFile && (
                    <p className="mt-1 text-xs text-gray-500">{imageFile.name}</p>
                  )}
                </div>
                {imagePreview && (
                  <img
                    src={imagePreview}
                    alt="미리보기"
                    className="h-20 w-20 object-cover rounded-lg border"
                  />
                )}
              </div>
            </div>

            <div className="col-span-2 border-t pt-4 mt-2">
              <p className="text-sm font-medium text-gray-700 mb-3">좌석 설정</p>
              <div className="grid grid-cols-4 gap-3">
                <div>
                  <label className="block text-xs text-gray-600 mb-1">구역명</label>
                  <input
                    name="seatSection"
                    value={form.seatSection}
                    onChange={handleChange}
                    className="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-xs text-gray-600 mb-1">열 수</label>
                  <input
                    type="number"
                    name="seatRowCount"
                    value={form.seatRowCount}
                    onChange={handleChange}
                    min={1}
                    className="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-xs text-gray-600 mb-1">열당 좌석 수</label>
                  <input
                    type="number"
                    name="seatsPerRow"
                    value={form.seatsPerRow}
                    onChange={handleChange}
                    min={1}
                    className="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-xs text-gray-600 mb-1">좌석 가격 (원)</label>
                  <input
                    type="number"
                    name="seatPrice"
                    value={form.seatPrice}
                    onChange={handleChange}
                    min={0}
                    step={1000}
                    className="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
              </div>
              <p className="text-xs text-gray-500 mt-2">
                총 좌석 수: {form.seatRowCount * form.seatsPerRow}석
              </p>
            </div>

            <div className="col-span-2 flex justify-end gap-3">
              <button
                type="button"
                onClick={() => {
                  setShowForm(false);
                  setImageFile(null);
                  setImagePreview(null);
                }}
                className="px-4 py-2 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 transition"
              >
                취소
              </button>
              <button
                type="submit"
                disabled={submitting}
                className={`px-6 py-2 rounded-lg text-white font-medium transition ${
                  submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-blue-600 hover:bg-blue-700'
                }`}
              >
                {submitting ? '등록 중...' : '등록'}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* 이벤트 목록 */}
      <div className="bg-white rounded-lg shadow-md overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b">
            <tr>
              <th className="text-left px-4 py-3 font-medium text-gray-600">공연명</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">장소</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">공연 일시</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">잔여 좌석</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">상태</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">관리</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {events.length === 0 ? (
              <tr>
                <td colSpan={6} className="text-center py-12 text-gray-500">
                  등록된 이벤트가 없습니다.
                </td>
              </tr>
            ) : (
              events.map((event) => (
                <tr key={event.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium">{event.title}</td>
                  <td className="px-4 py-3 text-gray-600">{event.venue}</td>
                  <td className="px-4 py-3 text-gray-600">
                    {new Date(event.startAt).toLocaleDateString('ko-KR', {
                      month: 'short',
                      day: 'numeric',
                      hour: '2-digit',
                      minute: '2-digit',
                    })}
                  </td>
                  <td className="px-4 py-3">
                    {event.availableSeats} / {event.totalSeats}
                  </td>
                  <td className="px-4 py-3">
                    <select
                      value={event.status}
                      onChange={(e) => handleStatusChange(event.id, e.target.value)}
                      className="text-xs px-2 py-1 border rounded focus:outline-none focus:ring-1 focus:ring-blue-500"
                    >
                      {Object.entries(statusLabels).map(([value, label]) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex gap-2">
                      <button
                        onClick={() => navigate(`/events/${event.id}`)}
                        className="px-2 py-1 text-xs text-blue-600 border border-blue-600 rounded hover:bg-blue-50 transition"
                      >
                        보기
                      </button>
                      <button
                        onClick={() => handleDelete(event.id)}
                        className="px-2 py-1 text-xs text-red-600 border border-red-600 rounded hover:bg-red-50 transition"
                      >
                        삭제
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
