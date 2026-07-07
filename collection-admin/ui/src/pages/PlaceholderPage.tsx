import { Card, List, Tag, Typography } from "antd";

type Props = {
  title: string;
  apis: string[];
};

export function PlaceholderPage({ title, apis }: Props) {
  return (
    <Card>
      <Typography.Title level={4}>{title}</Typography.Title>
      <Typography.Paragraph type="secondary">
        This page is the initial shell. Next step is wiring real API data and forms according to
        Phase 1 spec.
      </Typography.Paragraph>
      <List
        header={<Tag color="blue">Planned API Contracts</Tag>}
        dataSource={apis}
        renderItem={(item) => <List.Item>{item}</List.Item>}
      />
    </Card>
  );
}
