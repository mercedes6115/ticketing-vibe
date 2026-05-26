import { create } from 'zustand';
import type { Seat, SeatStatusMessage } from '../types';

interface SeatState {
  seats: Seat[];
  selectedSeat: Seat | null;
  setSeats: (seats: Seat[]) => void;
  setSelectedSeat: (seat: Seat | null) => void;
  updateSeatStatus: (message: SeatStatusMessage) => void;
}

export const useSeatStore = create<SeatState>((set) => ({
  seats: [],
  selectedSeat: null,
  setSeats: (seats) => set({ seats }),
  setSelectedSeat: (seat) => set({ selectedSeat: seat }),
  updateSeatStatus: (message) =>
    set((state) => ({
      seats: state.seats.map((seat) =>
        seat.id === message.seatId
          ? { ...seat, status: message.status }
          : seat
      ),
      // 선택한 좌석이 다른 사람에게 선점되면 선택 해제
      selectedSeat:
        state.selectedSeat?.id === message.seatId && message.status !== 'AVAILABLE'
          ? null
          : state.selectedSeat,
    })),
}));
