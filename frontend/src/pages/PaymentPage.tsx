import { useRef, useEffect, useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { bookingApi } from '../api/bookings';
import { useUserStore } from '../stores/userStore';
import type { Seat } from '../types';

type PaymentMethod = 'CARD' | 'BANK_TRANSFER' | 'VIRTUAL_ACCOUNT';

const POLL_INTERVAL_MS = 1000;
const POLL_TIMEOUT_MS = 30000;
const MAX_NETWORK_ERRORS = 3;

const pollStatus = (bookingNo: string, signal: AbortSignal): Promise<void> => {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + POLL_TIMEOUT_MS;
    let networkErrors = 0;

    const tick = async () => {
      if (signal.aborted) return;
      try {
        const res = await bookingApi.getStatus(bookingNo);
        networkErrors = 0;
        const { status } = res.data;
        if (status === 'CONFIRMED') { resolve(); return; }
        if (status === 'FAILED') { reject(new Error('예매 처리에 실패했습니다.')); return; }
        if (Date.now() >= deadline) {
          reject(new Error('예매 처리 시간이 초과되었습니다. 잠시 후 예매 내역을 확인하세요.'));
          return;
        }
        setTimeout(tick, POLL_INTERVAL_MS);
      } catch {
        if (signal.aborted) return;
        networkErrors++;
        if (networkErrors >= MAX_NETWORK_ERRORS) {
          reject(new Error('상태 조회 중 오류가 발생했습니다.'));
          return;
        }
        setTimeout(tick, POLL_INTERVAL_MS);
      }
    };

    tick();
  });
};

export default function PaymentPage() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const { user } = useUserStore();
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>('CARD');
  const [processing, setProcessing] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');
  const abortControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      abortControllerRef.current?.abort();
    };
  }, []);

  const seat = location.state?.seat as Seat | undefined;
  const eventId = Number(id);

  if (!user) return null;

  if (!seat) {
    return (
      <div className="max-w-lg mx-auto px-4 py-8 text-center">
        <p className="text-gray-500">선택된 좌석이 없습니다.</p>
        <button
          onClick={() => navigate(`/events/${eventId}/seats`)}
          className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg"
        >
          좌석 선택으로 돌아가기
        </button>
      </div>
    );
  }

  const handlePayment = async () => {
    setProcessing(true);
    setStatusMessage('예매 요청 중...');
    const controller = new AbortController();
    abortControllerRef.current = controller;
    try {
      const res = await bookingApi.create({
        seatId: seat.id,
        paymentMethod,
      });
      const { bookingNo } = res.data;

      setStatusMessage('예매 처리 중... (최대 30초)');
      await pollStatus(bookingNo, controller.signal);

      navigate(`/bookings/${bookingNo}/complete`);
    } catch (error) {
      const err = error as { response?: { data?: { message?: string } }; message?: string };
      alert(err.response?.data?.message || err.message || '결제에 실패했습니다.');
      setProcessing(false);
      setStatusMessage('');
    }
  };

  const paymentMethods: { value: PaymentMethod; label: string; icon: string }[] = [
    { value: 'CARD', label: '신용/체크카드', icon: '💳' },
    { value: 'BANK_TRANSFER', label: '계좌이체', icon: '🏦' },
    { value: 'VIRTUAL_ACCOUNT', label: '가상계좌', icon: '📄' },
  ];

  return (
    <div className="max-w-lg mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">결제하기</h1>

      {/* 예매 정보 */}
      <div className="bg-white rounded-lg shadow-md p-6 mb-6">
        <h2 className="font-semibold text-gray-900 mb-4">예매 정보</h2>
        <div className="space-y-2 text-sm">
          <div className="flex justify-between">
            <span className="text-gray-500">좌석</span>
            <span className="font-medium">
              {seat.section} {seat.seatRow}열 {seat.seatNumber}번
            </span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">가격</span>
            <span className="font-medium">{seat.price.toLocaleString()}원</span>
          </div>
        </div>
      </div>

      {/* 결제 수단 선택 */}
      <div className="bg-white rounded-lg shadow-md p-6 mb-6">
        <h2 className="font-semibold text-gray-900 mb-4">결제 수단</h2>
        <div className="space-y-2">
          {paymentMethods.map((method) => (
            <label
              key={method.value}
              className={`flex items-center gap-3 p-4 rounded-lg border-2 cursor-pointer transition ${
                paymentMethod === method.value
                  ? 'border-blue-600 bg-blue-50'
                  : 'border-gray-200 hover:border-gray-300'
              }`}
            >
              <input
                type="radio"
                name="paymentMethod"
                value={method.value}
                checked={paymentMethod === method.value}
                onChange={(e) => setPaymentMethod(e.target.value as PaymentMethod)}
                className="hidden"
              />
              <span className="text-2xl">{method.icon}</span>
              <span className="font-medium">{method.label}</span>
            </label>
          ))}
        </div>
      </div>

      {/* 결제 금액 */}
      <div className="bg-white rounded-lg shadow-md p-6 mb-6">
        <div className="flex justify-between items-center">
          <span className="text-lg font-semibold">총 결제 금액</span>
          <span className="text-2xl font-bold text-blue-600">
            {seat.price.toLocaleString()}원
          </span>
        </div>
      </div>

      {/* 결제 버튼 */}
      <button
        onClick={handlePayment}
        disabled={processing}
        className={`w-full py-4 rounded-lg font-semibold text-white transition ${
          processing
            ? 'bg-gray-400 cursor-not-allowed'
            : 'bg-blue-600 hover:bg-blue-700'
        }`}
      >
        {processing ? statusMessage || '처리 중...' : `${seat.price.toLocaleString()}원 결제하기`}
      </button>

      {/* 안내 */}
      <p className="mt-4 text-xs text-gray-500 text-center">
        * 테스트 환경으로 실제 결제가 이루어지지 않습니다.
      </p>
    </div>
  );
}
