import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { seatApi } from '../api/seats';
import { eventApi } from '../api/events';
import { useUserStore } from '../stores/userStore';
import { useSeatStore } from '../stores/seatStore';
import type { Seat, SeatStatusMessage, Event } from '../types';

const sortLabels = (a: string, b: string) => {
  const na = Number(a);
  const nb = Number(b);
  if (!Number.isNaN(na) && !Number.isNaN(nb)) return na - nb;
  return a.localeCompare(b);
};

export default function SeatSelectPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useUserStore();
  const { seats, selectedSeat, setSeats, setSelectedSeat, updateSeatStatus } = useSeatStore();
  const [event, setEvent] = useState<Event | null>(null);
  const [loading, setLoading] = useState(true);
  const [holdingTimer, setHoldingTimer] = useState<number | null>(null);
  const stompClientRef = useRef<Client | null>(null);

  const eventId = Number(id);

  useEffect(() => {
    if (!user) return;

    const loadData = async () => {
      try {
        const [eventRes, seatsRes] = await Promise.all([
          eventApi.getById(eventId),
          seatApi.getByEventId(eventId),
        ]);
        setEvent(eventRes.data);
        setSeats(seatsRes.data);
      } catch (error) {
        console.error('Failed to load data:', error);
      } finally {
        setLoading(false);
      }
    };

    const client = new Client({
      webSocketFactory: () => new SockJS('http://localhost:8080/ws'),
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe(`/topic/events/${eventId}/seats`, (message) => {
          const data: SeatStatusMessage = JSON.parse(message.body);
          updateSeatStatus(data);
        });
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame);
      },
    });

    loadData();
    client.activate();
    stompClientRef.current = client;

    return () => {
      client.deactivate();
      stompClientRef.current = null;
    };
  }, [eventId, setSeats, updateSeatStatus, user]);

  useEffect(() => {
    let interval: ReturnType<typeof setInterval> | undefined;
    if (selectedSeat && holdingTimer !== null && holdingTimer > 0) {
      interval = setInterval(() => {
        setHoldingTimer((prev) => (prev !== null ? prev - 1 : null));
      }, 1000);
    }
    return () => {
      if (interval) clearInterval(interval);
    };
  }, [selectedSeat, holdingTimer]);

  const sections = useMemo(() => {
    const bySection = seats.reduce((acc, seat) => {
      if (!acc[seat.section]) acc[seat.section] = [];
      acc[seat.section].push(seat);
      return acc;
    }, {} as Record<string, Seat[]>);

    return Object.entries(bySection)
      .sort(([a], [b]) => sortLabels(a, b))
      .map(([section, sectionSeats]) => {
        const byRow = sectionSeats.reduce((acc, seat) => {
          if (!acc[seat.seatRow]) acc[seat.seatRow] = [];
          acc[seat.seatRow].push(seat);
          return acc;
        }, {} as Record<string, Seat[]>);

        const rows = Object.entries(byRow)
          .sort(([a], [b]) => sortLabels(a, b))
          .map(([row, rowSeats]) => ({
            row,
            seats: rowSeats.sort((a, b) => a.seatNumber - b.seatNumber),
          }));

        const maxColumns = Math.max(...rows.map((row) => row.seats.length), 1);
        const available = sectionSeats.filter((seat) => seat.status === 'AVAILABLE').length;

        return { section, rows, maxColumns, total: sectionSeats.length, available };
      });
  }, [seats]);

  if (!user) return null;

  const getSeatColor = (seat: Seat) => {
    if (selectedSeat?.id === seat.id) return 'bg-blue-600 text-white';
    switch (seat.status) {
      case 'AVAILABLE':
        return 'bg-green-100 hover:bg-green-200 cursor-pointer';
      case 'HOLD':
        return 'bg-yellow-200 cursor-not-allowed';
      case 'SOLD':
        return 'bg-gray-300 cursor-not-allowed';
      default:
        return 'bg-gray-100';
    }
  };

  const handleSeatClick = async (seat: Seat) => {
    if (seat.status !== 'AVAILABLE') return;

    if (selectedSeat) {
      try {
        await seatApi.release(selectedSeat.id);
      } catch (error) {
        console.error('Failed to release seat:', error);
      }
    }

    try {
      const res = await seatApi.hold(seat.id);
      setSelectedSeat(res.data);
      setHoldingTimer(res.data.holdExpiresInSeconds ?? 300);
    } catch (error: any) {
      alert(error.response?.data?.message || '좌석 선점에 실패했습니다.');
    }
  };

  const handleRelease = async () => {
    if (!selectedSeat) return;

    try {
      await seatApi.release(selectedSeat.id);
      setSelectedSeat(null);
      setHoldingTimer(null);
    } catch (error: any) {
      alert(error.response?.data?.message || '좌석 해제에 실패했습니다.');
    }
  };

  const handlePayment = () => {
    if (!selectedSeat) return;
    navigate(`/events/${eventId}/payment`, {
      state: { seat: selectedSeat },
    });
  };

  const formatTimer = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 pb-20">
      <div className="mb-6 flex items-start justify-between gap-4">
        <div>
          <h1 className="mb-2 text-2xl font-bold text-gray-900">{event?.title}</h1>
          <p className="text-sm text-gray-600">{event?.venue}</p>
        </div>
        <div className="text-right text-sm text-gray-600">
          <p>잔여 {event?.availableSeats.toLocaleString()}석</p>
          <p>총 {event?.totalSeats.toLocaleString()}석</p>
        </div>
      </div>

      <div className="mb-6 flex flex-wrap gap-4 text-sm">
        <div className="flex items-center gap-2">
          <div className="h-6 w-6 rounded bg-green-100" />
          <span>선택 가능</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="h-6 w-6 rounded bg-yellow-200" />
          <span>선점 중</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="h-6 w-6 rounded bg-gray-300" />
          <span>판매 완료</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="h-6 w-6 rounded bg-blue-600" />
          <span>내 선택</span>
        </div>
      </div>

      <div className="mb-8 rounded-t-lg bg-gray-800 py-3 text-center font-semibold tracking-wider text-white">
        STAGE
      </div>

      <div className="space-y-6">
        {sections.map(({ section, rows, maxColumns, total, available }) => (
          <section key={section} className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
            <div className="mb-4 flex items-center justify-between gap-4">
              <h2 className="text-lg font-semibold text-gray-800">{section} 구역</h2>
              <p className="text-sm text-gray-500">
                {available.toLocaleString()} / {total.toLocaleString()}석 가능
              </p>
            </div>

            <div className="overflow-x-auto pb-2">
              <div className="min-w-full space-y-2">
                {rows.map(({ row, seats: rowSeats }) => (
                  <div key={row} className="flex items-center gap-3">
                    <span className="w-10 shrink-0 text-right text-xs font-medium text-gray-500">
                      {row}열
                    </span>
                    <div
                      className="grid gap-1"
                      style={{
                        gridTemplateColumns: `repeat(${maxColumns}, 2rem)`,
                      }}
                    >
                      {rowSeats.map((seat) => (
                        <button
                          key={seat.id}
                          type="button"
                          onClick={() => handleSeatClick(seat)}
                          disabled={seat.status !== 'AVAILABLE'}
                          className={`h-8 w-8 rounded text-xs font-medium transition ${getSeatColor(seat)}`}
                          title={`${seat.section} ${seat.seatRow}열 ${seat.seatNumber}번 - ${seat.price.toLocaleString()}원`}
                        >
                          {seat.seatNumber}
                        </button>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </section>
        ))}
      </div>

      <div className="fixed bottom-0 left-0 right-0 border-t bg-white p-4 shadow-lg">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4">
          {selectedSeat ? (
            <>
              <div>
                <p className="font-semibold text-gray-900">
                  {selectedSeat.section} {selectedSeat.seatRow}열 {selectedSeat.seatNumber}번
                </p>
                <p className="font-bold text-blue-600">
                  {selectedSeat.price.toLocaleString()}원
                </p>
                {holdingTimer !== null && (
                  <p className="text-sm text-red-500">선점 시간: {formatTimer(holdingTimer)}</p>
                )}
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={handleRelease}
                  className="rounded-lg bg-gray-200 px-4 py-2 text-gray-700 transition hover:bg-gray-300"
                >
                  선택 취소
                </button>
                <button
                  type="button"
                  onClick={handlePayment}
                  className="rounded-lg bg-blue-600 px-6 py-2 font-semibold text-white transition hover:bg-blue-700"
                >
                  결제하기
                </button>
              </div>
            </>
          ) : (
            <p className="text-gray-500">좌석을 선택하세요.</p>
          )}
        </div>
      </div>
    </div>
  );
}
