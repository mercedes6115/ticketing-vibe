import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { queueApi, getQueueStreamUrl } from '../api/queue';
import { useUserStore } from '../stores/userStore';
import { useQueueStore } from '../stores/queueStore';
import type { QueueStatus } from '../types';

export default function QueuePage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user, accessToken } = useUserStore();
  const { status, setStatus, setToken, setIsInQueue } = useQueueStore();
  const [error, setError] = useState<string | null>(null);
  const eventSourceRef = useRef<EventSource | null>(null);

  const eventId = Number(id);

  useEffect(() => {
    if (!user || !accessToken) {
      navigate('/login');
      return;
    }
  }, [user, accessToken, navigate]);

  useEffect(() => {
    if (!user || !accessToken) return;

    let navigated = false;

    // SSE 연결 — JWT를 ?token= 쿼리 파라미터로 전달 (EventSource는 커스텀 헤더 미지원)
    const url = getQueueStreamUrl(eventId, accessToken);
    const eventSource = new EventSource(url);
    eventSourceRef.current = eventSource;

    eventSource.addEventListener('queue-status', (e) => {
      const data: QueueStatus = JSON.parse(e.data);
      setStatus(data);
    });

    // 서버 스케줄러가 토큰을 발급했을 때 자동 이동
    eventSource.addEventListener('token-issued', () => {
      if (navigated) return;
      navigated = true;
      eventSource.close();
      navigate(`/events/${eventId}/seats`);
    });

    eventSource.onerror = () => {
      if (navigated) return;
      setError('연결이 끊어졌습니다. 다시 시도해주세요.');
      eventSource.close();
    };

    return () => {
      eventSource.close();
    };
  }, [eventId, accessToken, setStatus, navigate, user]);

  const handleIssueToken = async () => {
    try {
      const res = await queueApi.issueToken(eventId);
      setToken(res.data);
      navigate(`/events/${eventId}/seats`);
    } catch (error: any) {
      alert(error.response?.data?.message || '토큰 발급에 실패했습니다.');
    }
  };

  const handleExit = async () => {
    if (!confirm('대기열에서 나가시겠습니까?')) return;

    try {
      await queueApi.exit(eventId);
      setIsInQueue(false);
      setStatus(null);
      navigate(`/events/${eventId}`);
    } catch (error) {
      console.error('Failed to exit queue:', error);
    }
  };

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}분 ${secs}초`;
  };

  const progressPercent = status
    ? Math.max(0, Math.min(100, ((status.totalWaiting - status.position + 1) / status.totalWaiting) * 100))
    : 0;

  return (
    <div className="max-w-lg mx-auto px-4 py-8">
      <div className="bg-white rounded-lg shadow-md p-8 text-center">
        <h1 className="text-2xl font-bold text-gray-900 mb-6">대기열</h1>

        {error ? (
          <div className="text-red-600 mb-4">{error}</div>
        ) : !status ? (
          <div className="flex justify-center items-center h-32">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
          </div>
        ) : (
          <>
            {/* 순번 표시 */}
            <div className="mb-8">
              <div className="text-6xl font-bold text-blue-600 mb-2">
                {status.position}
                <span className="text-2xl text-gray-400">번</span>
              </div>
              <p className="text-gray-500">
                전체 대기 인원: {status.totalWaiting}명
              </p>
            </div>

            {/* 진행 바 */}
            <div className="mb-6">
              <div className="h-3 bg-gray-200 rounded-full overflow-hidden">
                <div
                  className="h-full bg-blue-600 transition-all duration-500"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>
            </div>

            {/* 예상 대기 시간 */}
            {!status.canEnter && (
              <p className="text-gray-600 mb-6">
                예상 대기 시간: <strong>{formatTime(status.estimatedWaitSeconds)}</strong>
              </p>
            )}

            {/* 입장 버튼 */}
            {status.canEnter ? (
              <div className="space-y-4">
                <div className="bg-green-50 border border-green-200 rounded-lg p-4 mb-4">
                  <p className="text-green-800 font-semibold">
                    🎉 입장 가능합니다!
                  </p>
                  <p className="text-green-600 text-sm">
                    아래 버튼을 눌러 좌석을 선택하세요.
                  </p>
                </div>
                <button
                  onClick={handleIssueToken}
                  className="w-full py-3 bg-blue-600 text-white rounded-lg font-semibold hover:bg-blue-700 transition"
                >
                  좌석 선택하러 가기
                </button>
              </div>
            ) : (
              <div className="space-y-4">
                <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
                  <p className="text-yellow-800">
                    잠시만 기다려주세요...
                  </p>
                </div>
                <button
                  onClick={handleExit}
                  className="w-full py-3 bg-gray-200 text-gray-700 rounded-lg font-semibold hover:bg-gray-300 transition"
                >
                  대기열 나가기
                </button>
              </div>
            )}
          </>
        )}
      </div>

      {/* 안내 사항 */}
      <div className="mt-6 bg-gray-50 rounded-lg p-4 text-sm text-gray-600">
        <h3 className="font-semibold mb-2">안내 사항</h3>
        <ul className="list-disc list-inside space-y-1">
          <li>페이지를 새로고침하면 대기 순번이 초기화될 수 있습니다.</li>
          <li>입장 가능 알림 후 10분 이내에 좌석을 선택해주세요.</li>
          <li>선택한 좌석은 5분간 임시 선점됩니다.</li>
        </ul>
      </div>
    </div>
  );
}
