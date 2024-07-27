import unittest
import requests

BASE_URL = "http://localhost:8080"

class TestGameRoom(unittest.TestCase):

    def setUp(self):
        response = requests.post(f"{BASE_URL}/reset")
        self.assertEqual(response.status_code, 200)

    def create_session_and_join(self):
        session = requests.Session()
        response = session.post(f"{BASE_URL}/join")
        return session, response.json()

    def create_session_and_join_specific_room(self, room_id):
        session = requests.Session()
        response = session.post(f"{BASE_URL}/join/{room_id}")
        return session, response.json()

    def test_join_new_session(self):
        session, response_json = self.create_session_and_join()
        self.assertEqual(response_json["message"], "Waiting for more players...")

    def test_join_same_session(self):
        session = requests.Session()
        response1 = session.post(f"{BASE_URL}/join").json()
        response2 = session.post(f"{BASE_URL}/join").json()

        self.assertEqual(response2["message"], "You are already in the game.")

    def test_multiple_sessions_fill_room(self):
        sessions = []
        responses = []

        for _ in range(4):
            session, response_json = self.create_session_and_join()
            sessions.append(session)
            responses.append(response_json)

        for i, response_json in enumerate(responses):
            if i < 3:
                self.assertEqual(response_json["message"], "Waiting for more players...")
            else:
                self.assertEqual(response_json["message"], "Game room created! The game is starting.")

    def test_room_reset_after_filled(self):
        sessions = []
        responses = []

        for _ in range(4):
            session, response_json = self.create_session_and_join()
            sessions.append(session)
            responses.append(response_json)

        session5, response5_json = self.create_session_and_join()

        for i, response_json in enumerate(responses):
            if i < 3:
                self.assertEqual(response_json["message"], "Waiting for more players...")
            else:
                self.assertEqual(response_json["message"], "Game room created! The game is starting.")

        self.assertEqual(response5_json["message"], "Waiting for more players...")

    def test_join_specific_room(self):
        session, response_json = self.create_session_and_join()
        room_id = response_json["roomId"]
        self.assertIsNotNone(room_id)

        session2, response2_json = self.create_session_and_join_specific_room(room_id)
        self.assertEqual(response2_json["message"], "Waiting for more players...")

        session3, response3_json = self.create_session_and_join_specific_room(room_id)
        self.assertEqual(response3_json["message"], "Waiting for more players...")

        session4, response4_json = self.create_session_and_join_specific_room(room_id)
        self.assertEqual(response4_json["message"], "Game room created! The game is starting.")

if __name__ == "__main__":
    unittest.main()
