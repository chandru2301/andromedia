class AnomalyEvent:
    def __init__(self, event_id):
        self.event_id = event_id

def create_event(event_id):
    return AnomalyEvent(event_id)
